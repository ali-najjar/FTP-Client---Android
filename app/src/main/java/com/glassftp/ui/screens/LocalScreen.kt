package com.glassftp.ui.screens

import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.view.View
import com.glassftp.App
import com.glassftp.R
import com.glassftp.core.FileKind
import com.glassftp.core.FileShareProvider
import com.glassftp.core.Format
import com.glassftp.ui.Action
import com.glassftp.ui.Card
import com.glassftp.ui.Navigator
import com.glassftp.ui.OverlayHost
import com.glassftp.ui.TextStyle
import com.glassftp.ui.Theme
import com.glassftp.ui.confirm
import com.glassftp.ui.dp
import com.glassftp.ui.filledButton
import com.glassftp.ui.linear
import com.glassftp.ui.prompt
import com.glassftp.ui.text
import com.glassftp.ui.tile
import com.glassftp.ui.hbox
import com.glassftp.ui.vbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Browses phone storage. Also used as a picker for uploads and for choosing folders. */
class LocalScreen(
    nav: Navigator,
    private val mode: Mode = Mode.BROWSE,
    private val onPickFolder: ((File) -> Unit)? = null,
    private val onPick: ((List<File>) -> Unit)? = null,
) : BrowserScreen(nav) {
    enum class Mode { BROWSE, PICK_FILES, PICK_FOLDER }

    constructor(nav: Navigator, mode: Mode, onPick: (List<File>) -> Unit) : this(nav, mode, null, onPick)

    private class Root(val label: String, val dir: File, val icon: Int)

    private var roots: List<Root> = emptyList()

    override val hidesTabBar get() = mode != Mode.BROWSE
    override val navigateInSelection get() = mode == Mode.PICK_FILES

    init {
        navigate("", remember = false)
    }

    override fun onAppear() {
        if (mode == Mode.PICK_FILES && !selecting && isBuilt) view.post { if (!selecting) enterSelection() }
        // Permission may have changed while away.
        if (path.isEmpty() || errorText != null) refresh()
    }

    override fun exitSelection() {
        if (mode == Mode.PICK_FILES) nav.pop() else super.exitSelection()
    }

    private fun buildRoots(): List<Root> {
        val out = mutableListOf<Root>()
        val internal = Environment.getExternalStorageDirectory()
        out += Root(str(R.string.internal_storage), internal, R.drawable.ic_phone)
        out += Root(str(R.string.downloads), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), R.drawable.ic_download)
        val dl = App.prefs.downloadDir
        if (!dl.absolutePath.startsWith(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/") ||
            dl.exists()) out += Root(str(R.string.glassftp_downloads), dl, R.drawable.ic_transfers)
        out += Root(str(R.string.app_files), App.prefs.privateDownloadDir(), R.drawable.ic_lock)
        ctx.getExternalFilesDirs(null).drop(1).filterNotNull().forEach { f ->
            val p = f.absolutePath.substringBefore("/Android/data")
            out += Root(str(R.string.sd_card), File(p), R.drawable.ic_sdcard)
        }
        return out.distinctBy { it.dir.absolutePath }
    }

    override fun titleFor(path: String): CharSequence {
        if (path.isEmpty()) return str(R.string.on_my_phone)
        roots.firstOrNull { it.dir.absolutePath == path }?.let { return it.label }
        return File(path).name
    }

    override fun crumbs(path: String): List<Pair<String, String>> {
        val out = mutableListOf(str(R.string.on_my_phone) to "")
        if (path.isEmpty()) return out
        val root = roots.filter { path == it.dir.absolutePath || path.startsWith(it.dir.absolutePath + "/") }
            .maxByOrNull { it.dir.absolutePath.length }
        var acc: String
        val rest: String
        if (root != null) {
            out += root.label to root.dir.absolutePath
            acc = root.dir.absolutePath
            rest = path.removePrefix(acc)
        } else {
            acc = ""
            rest = path
        }
        rest.split('/').filter { it.isNotEmpty() }.forEach { seg ->
            acc = "$acc/$seg"
            out += seg to acc
        }
        return out
    }

    override suspend fun load(path: String): List<Item> {
        if (path.isEmpty()) {
            roots = buildRoots()
            return roots.mapIndexed { i, r -> Item(r.label, r.dir.absolutePath, true, -1, 0, payload = i) }
        }
        val dir = File(path)
        if (!dir.exists()) withContext(Dispatchers.IO) { dir.mkdirs() }
        val files = dir.listFiles() ?: throw IOException(str(R.string.no_access))
        return files.map { f -> Item(f.name, f.absolutePath, f.isDirectory, if (f.isDirectory) -1 else f.length(), f.lastModified(), payload = f) }
    }

    override fun itemDetail(item: Item): String {
        if (path.isEmpty()) {
            val r = roots.getOrNull(item.payload as? Int ?: -1) ?: return ""
            return runCatching {
                val st = StatFs(r.dir.absolutePath.let { p -> if (File(p).exists()) p else Environment.getExternalStorageDirectory().absolutePath })
                str(R.string.free_of, Format.size(st.availableBytes), Format.size(st.totalBytes))
            }.getOrDefault("")
        }
        if (item.isDir) return Format.date(item.modified)
        return super.itemDetail(item)
    }

    override fun headerExtra(): View? {
        if (nav.activity.hasStorageAccess()) return null
        val card = Card(ctx)
        card.addRow(ctx.vbox {
            setPadding(16.dp, 16.dp, 16.dp, 16.dp)
            addView(ctx.hbox {
                addView(ctx.tile(R.drawable.ic_folder, Theme.accent), linear(30.dp, 30.dp, end = 12.dp))
                addView(ctx.text(str(R.string.storage_title), TextStyle.Headline), linear(0, BrowserScreen.WRAP, 1f))
            })
            addView(ctx.text(str(R.string.storage_msg), TextStyle.Subhead, Theme.secondaryLabel), linear(top = 8.dp))
            addView(ctx.filledButton(str(R.string.allow)) {
                nav.activity.requestStorageAccess { refresh() }
            }, linear(top = 14.dp))
        })
        return ctx.vbox { addView(card, linear(bottom = 14.dp)) }
    }

    override fun emptyText(): CharSequence = str(R.string.folder_empty)

    override fun navActions(): Array<View> = when (mode) {
        Mode.PICK_FOLDER -> arrayOf(
            navBar.group(R.drawable.ic_folder_add to { _ -> newFolder() }),
            navBar.textButton(str(R.string.choose), prominent = true) { _ ->
                if (path.isNotEmpty()) { onPickFolder?.invoke(File(path)); nav.pop() }
            },
        )
        else -> arrayOf(navBar.group(
            R.drawable.ic_folder_add to { _ -> newFolder() },
            R.drawable.ic_more to { _ -> moreMenu() },
        ))
    }

    private fun moreMenu() {
        val p = App.prefs
        overlay.sheet(null, null, listOf(
            Action(str(R.string.select), icon = R.drawable.ic_select) { if (path.isNotEmpty()) enterSelection() },
            Action(str(R.string.sort_by), icon = R.drawable.ic_sort) { showSortMenu(this) { renderList() } },
            Action(str(if (p.showHidden) R.string.hide_hidden else R.string.show_hidden), icon = R.drawable.ic_eye) {
                p.showHidden = !p.showHidden; renderList()
            },
            Action(str(R.string.refresh), icon = R.drawable.ic_refresh) { refresh() },
        ))
    }

    private fun newFolder() {
        if (path.isEmpty()) return
        scope.launch {
            val name = overlay.prompt(str(R.string.new_folder), null, OverlayHost.Input(str(R.string.folder_name), ""),
                str(R.string.create), str(R.string.cancel))?.trim()
            if (name.isNullOrEmpty()) return@launch
            if (!File(path, name).mkdirs()) overlay.toast(str(R.string.operation_failed), R.drawable.ic_close, Theme.red)
            refresh()
        }
    }

    private fun activeRemote(): RemoteScreen? = nav.all().filterIsInstance<RemoteScreen>().lastOrNull { it.connected }

    override fun onOpen(item: Item) {
        if (mode != Mode.BROWSE) return
        onItemMenu(item)
    }

    override fun onItemMenu(item: Item) {
        if (path.isEmpty() || mode != Mode.BROWSE) { if (item.isDir) navigate(item.path); return }
        val f = File(item.path)
        val remote = activeRemote()
        val actions = mutableListOf<Action>()
        if (f.isDirectory) actions += Action(str(R.string.open), icon = R.drawable.ic_folder) { navigate(f.absolutePath) }
        else {
            actions += Action(str(R.string.open), icon = R.drawable.ic_open) { openExternally(nav, f, false) }
            actions += Action(str(R.string.share), icon = R.drawable.ic_share) { openExternally(nav, f, true) }
        }
        if (remote != null) actions += Action(str(R.string.upload_to_server, remote.site.displayName), icon = R.drawable.ic_upload) {
            App.transfers.upload(remote.site, f, remote.path)
            overlay.toast(str(R.string.queued_n, 1), R.drawable.ic_upload)
        }
        actions += Action(str(R.string.rename), icon = R.drawable.ic_edit) { rename(f) }
        actions += Action(str(R.string.get_info), icon = R.drawable.ic_info) {
            RemoteScreen.showInfo(this, f.name, listOf(
                str(R.string.kind) to (if (f.isDirectory) str(R.string.folder) else FileKind.mime(f.name)),
                str(R.string.size) to (if (f.isDirectory) "—" else "${Format.size(f.length())} (${f.length()})"),
                str(R.string.modified) to Format.dateTime(f.lastModified()),
                str(R.string.where) to (f.parent ?: ""),
            ))
        }
        actions += Action(str(R.string.delete), Action.Style.DESTRUCTIVE, R.drawable.ic_trash) { delete(listOf(f)) }
        overlay.sheet(f.name, if (f.isDirectory) null else Format.size(f.length()), actions)
    }

    override fun selectionActions(): List<Action> {
        if (mode == Mode.PICK_FILES) return listOf(
            Action(str(R.string.upload), icon = R.drawable.ic_upload) {
                val files = selectedPaths().map(::File)
                onPick?.invoke(files)
                nav.pop()
            },
        )
        val out = mutableListOf<Action>()
        activeRemote()?.let { remote ->
            out += Action(str(R.string.upload), icon = R.drawable.ic_upload) {
                val files = selectedPaths().map(::File)
                files.forEach { App.transfers.upload(remote.site, it, remote.path) }
                overlay.toast(str(R.string.queued_n, files.size), R.drawable.ic_upload)
                exitSelection()
            }
        }
        out += Action(str(R.string.share), icon = R.drawable.ic_share) {
            val files = selectedPaths().map(::File).filter { it.isFile }
            if (files.isNotEmpty()) shareMany(files)
        }
        out += Action(str(R.string.delete), Action.Style.DESTRUCTIVE, R.drawable.ic_trash) {
            delete(selectedPaths().map(::File)) { exitSelection() }
        }
        return out
    }

    private fun shareMany(files: List<File>) {
        val uris = ArrayList(files.map { FileShareProvider.uriFor(ctx, it) })
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(i, null)) }
    }

    private fun rename(f: File) {
        scope.launch {
            val name = overlay.prompt(str(R.string.rename), null, OverlayHost.Input(str(R.string.name), f.name, selectName = f.isFile),
                str(R.string.rename), str(R.string.cancel))?.trim()
            if (name.isNullOrEmpty() || name == f.name) return@launch
            if (!f.renameTo(File(f.parentFile, name))) overlay.toast(str(R.string.operation_failed), R.drawable.ic_close, Theme.red)
            refresh()
        }
    }

    private fun delete(files: List<File>, after: () -> Unit = {}) {
        if (files.isEmpty()) return
        scope.launch {
            val msg = if (files.size == 1) str(R.string.delete_one_msg, files[0].name) else str(R.string.delete_many_msg, files.size)
            if (!overlay.confirm(str(R.string.delete_title), msg, str(R.string.delete), str(R.string.cancel), destructive = true)) return@launch
            after()
            val ok = withContext(Dispatchers.IO) { files.all { it.deleteRecursively() } }
            if (!ok) overlay.toast(str(R.string.operation_failed), R.drawable.ic_close, Theme.red)
            refresh()
        }
    }

    companion object {
        fun openExternally(nav: Navigator, f: File, share: Boolean) {
            val ctx = nav.activity
            val uri = FileShareProvider.uriFor(ctx, f)
            val mime = FileKind.mime(f.name)
            val intent = if (share) {
                Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null)
            } else {
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                ctx.startActivity(intent)
            } catch (e: Exception) {
                nav.overlay.toast(ctx.getString(R.string.no_app_found), R.drawable.ic_info, Theme.orange)
            }
        }
    }
}
