package com.glassftp.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import com.glassftp.App
import com.glassftp.R
import com.glassftp.core.AppLog
import com.glassftp.core.FileKind
import com.glassftp.core.FileShareProvider
import com.glassftp.core.Format
import com.glassftp.core.LoginFailedException
import com.glassftp.core.Protocol
import com.glassftp.core.RemoteClient
import com.glassftp.core.RemoteEntry
import com.glassftp.core.RemotePath
import com.glassftp.core.Site
import com.glassftp.core.UntrustedServerException
import com.glassftp.transfer.Direction
import com.glassftp.transfer.Transfer
import com.glassftp.transfer.TransferState
import com.glassftp.ui.Action
import com.glassftp.ui.ColorUtil
import com.glassftp.ui.Fonts
import com.glassftp.ui.Navigator
import com.glassftp.ui.OverlayHost
import com.glassftp.ui.RoundDrawable
import com.glassftp.ui.TextStyle
import com.glassftp.ui.Theme
import com.glassftp.ui.confirm
import com.glassftp.ui.dp
import com.glassftp.ui.hbox
import com.glassftp.ui.icon
import com.glassftp.ui.linear
import com.glassftp.ui.prompt
import com.glassftp.ui.text
import com.glassftp.ui.vbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.SocketException

/** Browses one server over a dedicated control connection (transfers use their own). */
class RemoteScreen(nav: Navigator, site: Site) : BrowserScreen(nav) {
    var site = site
        private set
    private var client: RemoteClient? = null
    private val mutex = Mutex()
    var connected = false
        private set
    private var connecting = false
    private var keepAlive: Job? = null
    private var reloadPending = false

    private val finishListener = { t: Transfer ->
        if (t.site.id == site.id && t.direction == Direction.UPLOAD && t.state == TransferState.DONE &&
            RemotePath.parent(t.remotePath) == path) scheduleReload()
    }

    init {
        App.transfers.addFinishListener(finishListener)
    }

    override fun titleFor(path: String): CharSequence =
        if (path.isEmpty() || path == "/") site.displayName else RemotePath.name(path)

    override fun crumbs(path: String): List<Pair<String, String>> =
        if (path.isEmpty()) listOf(site.displayName to "")
        else RemotePath.crumbs(path).map { p -> (if (p == "/") "/" else RemotePath.name(p)) to p }

    override fun emptyText(): CharSequence = if (connected) str(R.string.folder_empty) else str(R.string.connecting)

    override fun onAppear() {
        if (client == null && !connecting) connect()
    }

    override fun onDestroy() {
        App.transfers.removeFinishListener(finishListener)
        keepAlive?.cancel()
        val c = client
        client = null
        if (c != null) Thread { runCatching { c.close() } }.start()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ connection

    private fun connect() {
        connecting = true
        loading = true
        errorText = null
        render()
        scope.launch {
            try {
                val s = ensurePassword(site) ?: run { connecting = false; nav.remove(this@RemoteScreen); return@launch }
                site = s
                val c = open()
                client = c
                val home = withContext(Dispatchers.IO) { c.home() }
                connected = true
                connecting = false
                if (App.sites.get(site.id) != null) App.sites.touch(site.id)
                clearHistory()
                navigate(home, remember = false)
                startKeepAlive()
                nav.activity.requestNotifications()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                connecting = false
                loading = false
                errorText = e.message ?: e.javaClass.simpleName
                AppLog.error(site.displayName, "Could not connect: $errorText")
                render()
            }
        }
    }

    override fun onRetry() {
        if (connected) refresh() else connect()
    }

    /** Opens a connection, asking the user to trust unknown certificates/host keys and fix bad passwords. */
    private suspend fun open(): RemoteClient {
        while (true) {
            val c = RemoteClient.create(site, App.trust)
            try {
                withContext(Dispatchers.IO) { c.connect() }
                return c
            } catch (e: UntrustedServerException) {
                withContext(Dispatchers.IO) { c.close() }
                if (!askTrust(e)) throw IOException(str(R.string.connection_cancelled))
                App.trust.trust(e.key, e.fingerprint)
            } catch (e: LoginFailedException) {
                withContext(Dispatchers.IO) { c.close() }
                if (site.anonymous) throw e
                val pw = overlay.prompt(str(R.string.login_failed), e.message,
                    OverlayHost.Input(str(R.string.password), "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
                    str(R.string.connect), str(R.string.cancel)) ?: throw IOException(str(R.string.connection_cancelled))
                site = site.copy(password = pw)
            }
        }
    }

    private suspend fun ensurePassword(s: Site): Site? {
        val needs = !(s.anonymous && s.protocol.isFtp) && s.password.isEmpty() && s.privateKey.isEmpty()
        if (!needs) return s
        val pw = overlay.prompt(str(R.string.enter_password), str(R.string.enter_password_for, s.subtitle),
            OverlayHost.Input(str(R.string.password), "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            str(R.string.connect), str(R.string.cancel)) ?: return null
        return s.copy(password = pw)
    }

    private suspend fun askTrust(e: UntrustedServerException): Boolean = when (e.kind) {
        UntrustedServerException.Kind.CERTIFICATE -> overlay.confirm(str(R.string.trust_cert_title),
            str(R.string.trust_cert_msg, site.host, e.details, e.fingerprint), str(R.string.trust), str(R.string.cancel))
        UntrustedServerException.Kind.HOST_KEY_NEW -> overlay.confirm(str(R.string.trust_key_title),
            str(R.string.trust_key_msg, site.host, e.details), str(R.string.trust), str(R.string.cancel))
        UntrustedServerException.Kind.HOST_KEY_CHANGED -> overlay.confirm(str(R.string.key_changed_title),
            str(R.string.key_changed_msg, site.host, e.details), str(R.string.connect_anyway), str(R.string.cancel), destructive = true)
    }

    private fun startKeepAlive() {
        keepAlive?.cancel()
        keepAlive = scope.launch {
            while (isActive) {
                delay(45_000)
                if (mutex.tryLock()) {
                    try {
                        withContext(Dispatchers.IO) { runCatching { client?.keepAlive() } }
                    } finally {
                        mutex.unlock()
                    }
                }
            }
        }
    }

    /** Runs [block] on the browsing connection, transparently reconnecting once if it dropped. */
    private suspend fun <T> withClient(block: (RemoteClient) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val c = client ?: throw IOException(str(R.string.not_connected))
            try {
                block(c)
            } catch (e: IOException) {
                if (!looksDisconnected(c, e)) throw e
                AppLog.status(site.displayName, "Connection lost, reconnecting…")
                runCatching { c.close() }
                val fresh = RemoteClient.create(site, App.trust)
                fresh.connect()
                client = fresh
                block(fresh)
            }
        }
    }

    private fun looksDisconnected(c: RemoteClient, e: IOException): Boolean {
        if (!c.isConnected) return true
        if (e is SocketException || e is org.apache.commons.net.ftp.FTPConnectionClosedException) return true
        val m = e.message.orEmpty().lowercase()
        return listOf("broken pipe", "connection reset", "session is down", "not connected", "closed", "421").any { m.contains(it) }
    }

    override suspend fun load(path: String): List<Item> {
        if (!connected) return emptyList()
        return withClient { it.list(path) }.map { e ->
            Item(e.name, e.path, e.isDir, e.size, e.modified, e.isLink, e)
        }
    }

    private fun scheduleReload() {
        if (reloadPending) return
        reloadPending = true
        scope.launch {
            delay(600)
            reloadPending = false
            if (connected && !loading) refresh()
        }
    }

    // ------------------------------------------------------------------ header & nav

    override fun headerExtra(): View? {
        if (!connected) return null
        val secure = site.protocol != Protocol.FTP
        val color = if (secure) Theme.green else Theme.orange
        val label = when (site.protocol) {
            Protocol.SFTP -> str(R.string.sec_sftp)
            Protocol.FTPES, Protocol.FTPS -> str(R.string.sec_tls)
            Protocol.FTP -> str(R.string.sec_plain)
        }
        return ctx.hbox {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4.dp, 0, 4.dp, 12.dp)
            addView(ctx.icon(if (secure) R.drawable.ic_lock else R.drawable.ic_shield, color, 14.dp), linear(14.dp, 14.dp, end = 6.dp))
            addView(ctx.text("$label · ${site.subtitle}", TextStyle.Footnote, Theme.secondaryLabel, lines = 1))
        }
    }

    override fun navActions(): Array<View> = arrayOf(navBar.group(
        R.drawable.ic_upload to { _ -> uploadMenu() },
        R.drawable.ic_folder_add to { _ -> newFolder() },
        R.drawable.ic_more to { _ -> moreMenu() },
    ))

    private fun uploadMenu() {
        if (!connected) return
        overlay.sheet(str(R.string.upload_to, path), null, listOf(
            Action(str(R.string.from_files_app), icon = R.drawable.ic_import) { pickWithSystem() },
            Action(str(R.string.from_device_storage), icon = R.drawable.ic_phone) { pickFromStorage() },
        ))
    }

    private fun pickWithSystem() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        val target = path
        nav.activity.launch(i) { code, data ->
            if (code != Activity.RESULT_OK || data == null) return@launch
            val uris = mutableListOf<Uri>()
            data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris += cd.getItemAt(k).uri }
            if (uris.isEmpty()) data.data?.let { uris += it }
            uris.forEach { u ->
                runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                App.transfers.uploadUri(site, u, target)
            }
            if (uris.isNotEmpty()) overlay.toast(str(R.string.queued_n, uris.size), R.drawable.ic_upload)
        }
    }

    private fun pickFromStorage() {
        val target = path
        nav.activity.requestStorageAccess { ok ->
            if (!ok) { overlay.toast(str(R.string.storage_denied), R.drawable.ic_info, Theme.orange); return@requestStorageAccess }
            nav.push(LocalScreen(nav, LocalScreen.Mode.PICK_FILES) { files ->
                files.forEach { App.transfers.upload(site, it, target) }
                if (files.isNotEmpty()) overlay.toast(str(R.string.queued_n, files.size), R.drawable.ic_upload)
            })
        }
    }

    private fun newFolder() {
        if (!connected) return
        scope.launch {
            val name = overlay.prompt(str(R.string.new_folder), null, OverlayHost.Input(str(R.string.folder_name), ""),
                str(R.string.create), str(R.string.cancel))?.trim()
            if (name.isNullOrEmpty()) return@launch
            runOp { withClient { it.mkdir(RemotePath.join(path, name)) } }
        }
    }

    private fun moreMenu() {
        val p = App.prefs
        overlay.sheet(null, null, listOf(
            Action(str(R.string.select), icon = R.drawable.ic_select) { enterSelection() },
            Action(str(R.string.sort_by), icon = R.drawable.ic_sort) { sortMenu() },
            Action(str(if (p.showHidden) R.string.hide_hidden else R.string.show_hidden), icon = R.drawable.ic_eye) {
                p.showHidden = !p.showHidden; renderList()
            },
            Action(str(R.string.refresh), icon = R.drawable.ic_refresh) { refresh() },
            Action(str(R.string.go_to_folder), icon = R.drawable.ic_chevron_forward) { goTo() },
            Action(str(R.string.message_log), icon = R.drawable.ic_log) { nav.push(LogScreen(nav)) },
            Action(str(R.string.disconnect), Action.Style.DESTRUCTIVE, R.drawable.ic_power) { nav.remove(this) },
        ))
    }

    fun sortMenu() = showSortMenu(this) { renderList() }

    private fun goTo() {
        scope.launch {
            val p = overlay.prompt(str(R.string.go_to_folder), null, OverlayHost.Input("/path/to/folder", path, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI),
                str(R.string.go), str(R.string.cancel))?.trim()
            if (!p.isNullOrEmpty()) navigate(if (p.startsWith("/")) p else RemotePath.join(path, p))
        }
    }

    // ------------------------------------------------------------------ items

    override fun onOpen(item: Item) = onItemMenu(item)

    override fun onItemMenu(item: Item) {
        val e = item.payload as RemoteEntry
        val actions = mutableListOf<Action>()
        if (e.isDir) {
            actions += Action(str(R.string.open), icon = R.drawable.ic_folder) { navigate(e.path) }
            actions += Action(str(R.string.download_folder), icon = R.drawable.ic_download) { download(listOf(e)) }
        } else {
            actions += Action(str(R.string.open), icon = R.drawable.ic_open) { openFile(e, share = false) }
            if (FileKind.isEditable(e.name) && e.size < 2_000_000) actions += Action(str(R.string.edit), icon = R.drawable.ic_edit) { edit(e) }
            actions += Action(str(R.string.download), icon = R.drawable.ic_download) { download(listOf(e)) }
            actions += Action(str(R.string.share), icon = R.drawable.ic_share) { openFile(e, share = true) }
        }
        actions += Action(str(R.string.rename), icon = R.drawable.ic_edit) { rename(e) }
        actions += Action(str(R.string.permissions), icon = R.drawable.ic_lock) { chmod(e) }
        actions += Action(str(R.string.get_info), icon = R.drawable.ic_info) { info(e) }
        actions += Action(str(R.string.copy_path), icon = R.drawable.ic_copy) {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("path", e.path))
            overlay.toast(str(R.string.copied), R.drawable.ic_copy)
        }
        actions += Action(str(R.string.delete), Action.Style.DESTRUCTIVE, R.drawable.ic_trash) { delete(listOf(e)) }
        overlay.sheet(e.name, if (e.isDir) null else Format.size(e.size), actions)
    }

    override fun selectionActions(): List<Action> = listOf(
        Action(str(R.string.download), icon = R.drawable.ic_download) {
            val sel = selectedItems().map { it.payload as RemoteEntry }
            exitSelection(); download(sel)
        },
        Action(str(R.string.delete), Action.Style.DESTRUCTIVE, R.drawable.ic_trash) {
            val sel = selectedItems().map { it.payload as RemoteEntry }
            delete(sel) { exitSelection() }
        },
    )

    private fun downloadDir(then: (File) -> Unit) {
        val a = nav.activity
        if (a.hasStorageAccess()) { then(App.prefs.downloadDir); return }
        scope.launch {
            val grant = overlay.confirm(str(R.string.storage_title), str(R.string.storage_msg), str(R.string.allow), str(R.string.not_now))
            if (grant) a.requestStorageAccess { ok -> then(if (ok) App.prefs.downloadDir else App.prefs.privateDownloadDir()) }
            else then(App.prefs.privateDownloadDir())
        }
    }

    private fun download(entries: List<RemoteEntry>) {
        if (entries.isEmpty()) return
        downloadDir { dir ->
            entries.forEach { App.transfers.download(site, it.path, it.isDir, it.size, dir) }
            overlay.toast(str(R.string.queued_n, entries.size), R.drawable.ic_download)
        }
    }

    private fun cacheFile(e: RemoteEntry): File =
        File(ctx.cacheDir, "open/${site.id.take(8)}/${RemotePath.parent(e.path).hashCode()}/${e.name}")

    private fun openFile(e: RemoteEntry, share: Boolean) {
        val dest = cacheFile(e)
        overlay.toast(str(R.string.preparing, e.name), R.drawable.ic_download)
        App.transfers.download(site, e.path, false, e.size, dest.parentFile!!, com.glassftp.core.ExistsAction.OVERWRITE, silent = true) { t ->
            if (t.state != TransferState.DONE) { overlay.toast(str(R.string.toast_failed, e.name), R.drawable.ic_close, Theme.red); return@download }
            val f = t.localFile ?: return@download
            LocalScreen.openExternally(nav, f, share)
        }
    }

    private fun edit(e: RemoteEntry) {
        val dest = cacheFile(e)
        overlay.toast(str(R.string.preparing, e.name), R.drawable.ic_download)
        App.transfers.download(site, e.path, false, e.size, dest.parentFile!!, com.glassftp.core.ExistsAction.OVERWRITE, silent = true) { t ->
            if (t.state == TransferState.DONE) t.localFile?.let { nav.push(EditorScreen(nav, site, e.path, it)) }
            else overlay.toast(str(R.string.toast_failed, e.name), R.drawable.ic_close, Theme.red)
        }
    }

    private fun rename(e: RemoteEntry) {
        scope.launch {
            val name = overlay.prompt(str(R.string.rename), null, OverlayHost.Input(str(R.string.name), e.name, selectName = !e.isDir),
                str(R.string.rename), str(R.string.cancel))?.trim()
            if (name.isNullOrEmpty() || name == e.name) return@launch
            runOp { withClient { it.rename(e.path, RemotePath.join(RemotePath.parent(e.path), name)) } }
        }
    }

    private fun delete(entries: List<RemoteEntry>, after: () -> Unit = {}) {
        if (entries.isEmpty()) return
        scope.launch {
            val msg = if (entries.size == 1) str(R.string.delete_one_msg, entries[0].name) else str(R.string.delete_many_msg, entries.size)
            if (!overlay.confirm(str(R.string.delete_title), msg, str(R.string.delete), str(R.string.cancel), destructive = true)) return@launch
            after()
            runOp { withClient { c -> entries.forEach { c.deleteRecursive(it) } } }
        }
    }

    private fun chmod(e: RemoteEntry) {
        PermissionsDialog.show(this, e.name, e.permissions) { mode ->
            scope.launch { runOp { withClient { it.chmod(e.path, mode) } } }
        }
    }

    private fun info(e: RemoteEntry) {
        val rows = mutableListOf(
            str(R.string.kind) to (if (e.isDir) str(R.string.folder) else FileKind.mime(e.name)),
            str(R.string.size) to (if (e.isDir) "—" else "${Format.size(e.size)} (${e.size})"),
            str(R.string.modified) to Format.dateTime(e.modified),
            str(R.string.permissions) to (if (e.permissions.isEmpty()) "—" else "${e.permissions}  ${Format.octal(e.permissions)}"),
            str(R.string.owner) to e.owner.ifEmpty { "—" },
            str(R.string.group) to e.group.ifEmpty { "—" },
            str(R.string.where) to RemotePath.parent(e.path),
        )
        if (e.linkTarget.isNotEmpty()) rows += str(R.string.link_target) to e.linkTarget
        showInfo(this, e.name, rows)
    }

    /** Runs a modifying operation with error reporting, then refreshes. */
    private suspend fun runOp(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            overlay.alert(str(R.string.operation_failed), e.message ?: e.javaClass.simpleName, listOf(Action(str(R.string.ok), Action.Style.PREFERRED)))
        }
        refresh()
    }

    companion object {
        fun showInfo(screen: com.glassftp.ui.Screen, title: String, rows: List<Pair<String, String>>) {
            val ctx = screen.ctx
            val table = ctx.vbox {
                rows.forEach { (k, v) ->
                    addView(ctx.hbox {
                        gravity = Gravity.TOP
                        setPadding(0, 6.dp, 0, 6.dp)
                        addView(ctx.text(k, TextStyle.Subhead, Theme.secondaryLabel), linear(96.dp, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
                        addView(ctx.text(v, TextStyle.Subhead, Theme.label).apply { setTextIsSelectable(true); typeface = Fonts.weight(500) },
                            linear(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    })
                }
            }
            table.background = RoundDrawable(ColorUtil.alpha(Theme.fill, 0.8f), 14f.dp)
            table.setPadding(14.dp, 8.dp, 14.dp, 8.dp)
            screen.overlay.alert(title, null, listOf(Action(screen.str(R.string.done), Action.Style.PREFERRED)), content = table)
        }
    }
}
