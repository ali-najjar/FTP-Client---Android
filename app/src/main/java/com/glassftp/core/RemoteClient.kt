package com.glassftp.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One control connection to a server. Not thread-safe: callers serialize access. */
interface RemoteClient : Closeable {
    val site: Site
    val isConnected: Boolean

    fun connect()
    fun home(): String
    fun list(path: String): List<RemoteEntry>
    fun stat(path: String): RemoteEntry?
    fun mkdir(path: String)
    fun deleteFile(path: String)
    fun removeDir(path: String)
    fun rename(from: String, to: String)
    fun chmod(path: String, mode: Int)

    fun openRead(path: String, offset: Long): InputStream
    fun finishRead()
    fun openWrite(path: String, append: Boolean): OutputStream
    fun finishWrite()

    fun keepAlive()
    fun abort()

    fun mkdirs(path: String) {
        val crumbs = RemotePath.crumbs(path).drop(1)
        for (dir in crumbs) {
            val st = runCatching { stat(dir) }.getOrNull()
            if (st == null) runCatching { mkdir(dir) }
        }
    }

    fun deleteRecursive(entry: RemoteEntry, onItem: (String) -> Unit = {}) {
        if (entry.isDir && !entry.isLink) {
            list(entry.path).forEach { deleteRecursive(it, onItem) }
            onItem(entry.path)
            removeDir(entry.path)
        } else {
            onItem(entry.path)
            deleteFile(entry.path)
        }
    }

    companion object {
        fun create(site: Site, trust: TrustStore): RemoteClient =
            if (site.protocol == Protocol.SFTP) SftpClient(site, trust) else FtpClient(site, trust)
    }
}

object Streams {
    /** Copies until EOF, calling [onBytes] with each chunk size. Throws if [cancelled]. */
    fun copy(input: InputStream, output: OutputStream, cancelled: () -> Boolean, onBytes: (Int) -> Unit) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            if (cancelled()) throw TransferCancelledException()
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            onBytes(n)
        }
        output.flush()
    }
}

class TransferCancelledException : IOException("Cancelled")
