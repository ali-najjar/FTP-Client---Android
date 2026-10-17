package com.glassftp.transfer

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import com.glassftp.core.AppLog
import com.glassftp.core.ExistsAction
import com.glassftp.core.Prefs
import com.glassftp.core.RemoteClient
import com.glassftp.core.RemotePath
import com.glassftp.core.Site
import com.glassftp.core.Streams
import com.glassftp.core.TransferCancelledException
import com.glassftp.core.TrustStore
import com.glassftp.core.UntrustedServerException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

enum class Direction { DOWNLOAD, UPLOAD }

enum class TransferState { QUEUED, RUNNING, DONE, SKIPPED, FAILED, CANCELED;
    val finished get() = this == DONE || this == SKIPPED || this == FAILED || this == CANCELED
}

class Transfer(
    val site: Site,
    val direction: Direction,
    /** For downloads: the source file. For uploads: the destination file path. */
    var remotePath: String,
    /** For downloads: destination. For uploads from storage: source. */
    var localFile: File?,
    /** Upload source picked through the system file picker. */
    val localUri: Uri? = null,
    var name: String,
    /** Folder transfers are expanded into file transfers by the worker. */
    val isFolder: Boolean = false,
    /** Overrides the global "file exists" preference. */
    val existsAction: ExistsAction? = null,
    /** Hidden helper transfers (e.g. "open" previews) do not show a toast. */
    val silent: Boolean = false,
) {
    val id = nextId.incrementAndGet()
    @Volatile var state = TransferState.QUEUED
    @Volatile var total = -1L
    @Volatile var done = 0L
    @Volatile var speed = 0.0
    @Volatile var error: String? = null
    @Volatile var note: String? = null
    @Volatile internal var cancelRequested = false
    var finishedAt = 0L
    internal var onFinished: ((Transfer) -> Unit)? = null
    internal var resumeHint = false

    val progress get() = if (total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0) else 0.0
    val etaSeconds get() = if (speed > 1 && total > 0) ((total - done) / speed).toLong() else -1L

    companion object { private val nextId = AtomicLong() }
}

/** FileZilla-style transfer queue: each worker uses its own connection. */
class TransferManager(private val context: Context, private val prefs: Prefs, private val trust: TrustStore) {
    private val items = mutableListOf<Transfer>()
    private val listeners = mutableListOf<() -> Unit>()
    private val finishListeners = mutableListOf<(Transfer) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var activeWorkers = 0
    private var notifyPosted = false

    fun snapshot(): List<Transfer> = synchronized(items) { items.toList() }

    val isBusy get() = synchronized(items) { items.any { !it.state.finished } }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    fun addFinishListener(l: (Transfer) -> Unit) { finishListeners += l }
    fun removeFinishListener(l: (Transfer) -> Unit) { finishListeners -= l }

    fun enqueue(t: Transfer, onFinished: ((Transfer) -> Unit)? = null) {
        t.onFinished = onFinished
        synchronized(items) { items += t }
        changed()
        pump()
    }

    fun download(site: Site, remotePath: String, isDir: Boolean, size: Long, destDir: File, existsAction: ExistsAction? = null,
                 silent: Boolean = false, onFinished: ((Transfer) -> Unit)? = null): Transfer {
        val name = RemotePath.name(remotePath)
        val t = Transfer(site, Direction.DOWNLOAD, remotePath, File(destDir, name), null, name, isDir, existsAction, silent)
        t.total = if (isDir) -1 else size
        enqueue(t, onFinished)
        return t
    }

    fun upload(site: Site, local: File, remoteDir: String, existsAction: ExistsAction? = null,
               onFinished: ((Transfer) -> Unit)? = null): Transfer {
        val t = Transfer(site, Direction.UPLOAD, RemotePath.join(remoteDir, local.name), local, null, local.name,
            local.isDirectory, existsAction)
        t.total = if (local.isDirectory) -1 else local.length()
        enqueue(t, onFinished)
        return t
    }

    fun uploadUri(site: Site, uri: Uri, remoteDir: String): Transfer {
        var name = "file"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val t = Transfer(site, Direction.UPLOAD, RemotePath.join(remoteDir, name), null, uri, name)
        t.total = size
        enqueue(t)
        return t
    }

    fun cancel(t: Transfer) {
        t.cancelRequested = true
        if (t.state == TransferState.QUEUED) finish(t, TransferState.CANCELED)
        changed()
    }

    fun cancelAll() = snapshot().filter { !it.state.finished }.forEach(::cancel)

    fun retry(t: Transfer) {
        if (t.state != TransferState.FAILED && t.state != TransferState.CANCELED) return
        t.cancelRequested = false
        t.error = null
        t.resumeHint = true
        t.state = TransferState.QUEUED
        changed()
        pump()
    }

    fun retryFailed() = snapshot().filter { it.state == TransferState.FAILED }.forEach(::retry)

    fun clearFinished() {
        synchronized(items) { items.removeAll { it.state.finished } }
        changed()
    }

    fun remove(t: Transfer) {
        if (!t.state.finished) return
        synchronized(items) { items.remove(t) }
        changed()
    }

    private fun changed() {
        synchronized(items) {
            if (notifyPosted) return
            notifyPosted = true
        }
        main.postDelayed({
            synchronized(items) { notifyPosted = false }
            listeners.toList().forEach { it() }
        }, 200)
    }

    private fun pump() {
        val start: Int
        synchronized(items) {
            val queued = items.count { it.state == TransferState.QUEUED }
            start = (minOf(prefs.concurrentTransfers, queued) - activeWorkers).coerceAtLeast(0)
            activeWorkers += start
        }
        if (start > 0 || isBusy) startService()
        repeat(start) { scope.launch { workerLoop() } }
    }

    private fun startService() {
        main.post {
            val i = Intent(context, TransferService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            }.onFailure { AppLog.error("Queue", "Could not start transfer service: ${it.message}") }
        }
    }

    private fun takeNext(): Transfer? = synchronized(items) {
        val t = items.firstOrNull { it.state == TransferState.QUEUED }
        if (t == null) activeWorkers-- else t.state = TransferState.RUNNING
        t
    }

    private fun workerLoop() {
        var client: RemoteClient? = null
        try {
            while (true) {
                val t = takeNext() ?: break
                changed()
                try {
                    if (client == null || client.site.id != t.site.id || client.site.host != t.site.host || !client.isConnected) {
                        client?.close()
                        client = RemoteClient.create(t.site, trust).also { it.connect() }
                    }
                    run(client, t)
                    if (t.state == TransferState.RUNNING) finish(t, TransferState.DONE)
                } catch (e: TransferCancelledException) {
                    runCatching { client?.abort() }
                    client = null
                    finish(t, TransferState.CANCELED)
                } catch (e: Throwable) {
                    runCatching { client?.abort() }
                    client = null
                    t.error = when (e) {
                        is UntrustedServerException -> "Server identity is not trusted. Connect to it once from the browser."
                        else -> e.message ?: e.javaClass.simpleName
                    }
                    AppLog.error(t.site.displayName, "Transfer of ${t.name} failed: ${t.error}")
                    finish(t, if (t.cancelRequested) TransferState.CANCELED else TransferState.FAILED)
                }
            }
        } finally {
            runCatching { client?.close() }
        }
    }

    private fun finish(t: Transfer, state: TransferState) {
        if (t.state.finished) return
        t.state = state
        t.finishedAt = System.currentTimeMillis()
        changed()
        main.post {
            t.onFinished?.invoke(t)
            finishListeners.toList().forEach { it(t) }
        }
    }

    private fun run(c: RemoteClient, t: Transfer) {
        if (t.isFolder) {
            if (t.direction == Direction.DOWNLOAD) expandDownload(c, t) else expandUpload(c, t)
            return
        }
        if (t.direction == Direction.DOWNLOAD) download(c, t) else upload(c, t)
    }

    private fun policy(t: Transfer): ExistsAction =
        if (t.resumeHint) ExistsAction.RESUME else t.existsAction ?: prefs.existsAction

    private fun download(c: RemoteClient, t: Transfer) {
        var dest = t.localFile ?: error("No destination")
        dest.parentFile?.mkdirs()
        if (t.total < 0) t.total = c.stat(t.remotePath)?.size ?: -1
        var offset = 0L
        if (dest.exists()) {
            when (policy(t)) {
                ExistsAction.SKIP -> { t.note = "exists"; finish(t, TransferState.SKIPPED); return }
                ExistsAction.RENAME -> { dest = uniqueLocal(dest); t.localFile = dest; t.name = dest.name }
                ExistsAction.RESUME -> {
                    offset = dest.length()
                    if (t.total in 0..offset) { t.done = t.total; t.note = "complete"; finish(t, TransferState.SKIPPED); return }
                }
                ExistsAction.OVERWRITE -> {}
            }
        }
        AppLog.status(t.site.displayName, "Downloading ${t.remotePath}" + if (offset > 0) " from byte $offset" else "")
        t.done = offset
        val meter = SpeedMeter(t)
        FileOutputStream(dest, offset > 0).use { out ->
            c.openRead(t.remotePath, offset).use { Streams.copy(it, out, { t.cancelRequested }) { n -> meter.add(n) } }
            c.finishRead()
        }
        if (t.total < 0) t.total = t.done
        AppLog.status(t.site.displayName, "File transfer successful, transferred ${t.done - offset} bytes")
        if (!dest.absolutePath.startsWith(context.cacheDir.absolutePath)) {
            runCatching { MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null) }
        }
    }

    private fun upload(c: RemoteClient, t: Transfer) {
        val parent = RemotePath.parent(t.remotePath)
        c.mkdirs(parent)
        var offset = 0L
        val existing = c.stat(t.remotePath)
        if (existing != null && !existing.isDir) {
            when (policy(t)) {
                ExistsAction.SKIP -> { t.note = "exists"; finish(t, TransferState.SKIPPED); return }
                ExistsAction.RENAME -> { t.remotePath = uniqueRemote(c, t.remotePath); t.name = RemotePath.name(t.remotePath) }
                ExistsAction.RESUME -> {
                    offset = existing.size
                    if (t.total in 0..offset) { t.done = t.total; t.note = "complete"; finish(t, TransferState.SKIPPED); return }
                }
                ExistsAction.OVERWRITE -> {}
            }
        }
        AppLog.status(t.site.displayName, "Uploading ${t.name} to ${t.remotePath}")
        val input: InputStream = t.localFile?.let { FileInputStream(it) }
            ?: t.localUri?.let { context.contentResolver.openInputStream(it) }
            ?: error("Cannot open source")
        input.use { ins ->
            var skipped = 0L
            while (skipped < offset) {
                val n = ins.skip(offset - skipped)
                if (n <= 0) break
                skipped += n
            }
            t.done = offset
            val meter = SpeedMeter(t)
            c.openWrite(t.remotePath, offset > 0).use { Streams.copy(ins, it, { t.cancelRequested }) { n -> meter.add(n) } }
            c.finishWrite()
        }
        if (t.total < 0) t.total = t.done
        AppLog.status(t.site.displayName, "File transfer successful")
    }

    private fun expandDownload(c: RemoteClient, t: Transfer) {
        val root = t.localFile ?: error("No destination")
        t.note = "scanning"
        changed()
        var count = 0
        fun walk(remote: String, local: File) {
            if (t.cancelRequested) throw TransferCancelledException()
            local.mkdirs()
            for (e in c.list(remote)) {
                val dest = File(local, e.name)
                if (e.isDir) {
                    if (!e.isLink) walk(e.path, dest)
                } else {
                    val child = Transfer(t.site, Direction.DOWNLOAD, e.path, dest, null, e.name, false, t.existsAction)
                    child.total = e.size
                    synchronized(items) { items += child }
                    count++
                }
            }
        }
        walk(t.remotePath, root)
        t.note = "queued:$count"
        pump()
    }

    private fun expandUpload(c: RemoteClient, t: Transfer) {
        val root = t.localFile ?: error("No source")
        t.note = "scanning"
        changed()
        var count = 0
        fun walk(local: File, remote: String) {
            if (t.cancelRequested) throw TransferCancelledException()
            runCatching { c.mkdirs(remote) }
            for (f in local.listFiles().orEmpty().sortedBy { it.name }) {
                val dest = RemotePath.join(remote, f.name)
                if (f.isDirectory) walk(f, dest)
                else {
                    val child = Transfer(t.site, Direction.UPLOAD, dest, f, null, f.name, false, t.existsAction)
                    child.total = f.length()
                    synchronized(items) { items += child }
                    count++
                }
            }
        }
        walk(root, t.remotePath)
        t.note = "queued:$count"
        pump()
    }

    private fun uniqueLocal(f: File): File {
        val base = f.nameWithoutExtension
        val ext = f.extension.let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (true) {
            val c = File(f.parentFile, "$base ($i)$ext")
            if (!c.exists()) return c
            i++
        }
    }

    private fun uniqueRemote(c: RemoteClient, path: String): String {
        val dir = RemotePath.parent(path)
        val name = RemotePath.name(path)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val p = RemotePath.join(dir, "$base ($i)$ext")
            if (c.stat(p) == null) return p
            i++
        }
    }

    private inner class SpeedMeter(private val t: Transfer) {
        private var windowStart = System.nanoTime()
        private var windowBytes = 0L
        private var lastNotify = 0L

        fun add(n: Int) {
            t.done += n
            windowBytes += n
            val now = System.nanoTime()
            val dt = (now - windowStart) / 1e9
            if (dt >= 0.5) {
                val inst = windowBytes / dt
                t.speed = if (t.speed <= 0) inst else t.speed * 0.6 + inst * 0.4
                windowStart = now
                windowBytes = 0
            }
            if (now - lastNotify > 250_000_000L) {
                lastNotify = now
                changed()
            }
        }
    }
}
