package com.glassftp.ui.screens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.glassftp.App
import com.glassftp.R
import com.glassftp.core.FileKind
import com.glassftp.core.Format
import com.glassftp.core.SortMode
import com.glassftp.ui.Action
import com.glassftp.ui.ActivityIndicator
import com.glassftp.ui.CheckCircle
import com.glassftp.ui.ColorUtil
import com.glassftp.ui.Fonts
import com.glassftp.ui.GlassSurface
import com.glassftp.ui.GroupedRowBackground
import com.glassftp.ui.Navigator
import com.glassftp.ui.RoundDrawable
import com.glassftp.ui.Screen
import com.glassftp.ui.TabBar
import com.glassftp.ui.TextStyle
import com.glassftp.ui.Theme
import com.glassftp.ui.autoMirror
import com.glassftp.ui.dp
import com.glassftp.ui.field
import com.glassftp.ui.frame
import com.glassftp.ui.hbox
import com.glassftp.ui.icon
import com.glassftp.ui.linear
import com.glassftp.ui.onTap
import com.glassftp.ui.style
import com.glassftp.ui.text
import com.glassftp.ui.tintedButton
import com.glassftp.ui.vbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared list UI for the server and on-device browsers. */
abstract class BrowserScreen(nav: Navigator) : Screen(nav) {
    class Item(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val modified: Long,
        val isLink: Boolean = false,
        val payload: Any? = null,
    )

    var path = ""
        protected set
    private val history = ArrayList<String>()
    protected var items: List<Item> = emptyList()
    protected var shown: List<Item> = emptyList()
        private set
    var selecting = false
        private set
    protected val selected = LinkedHashSet<String>()
    private var query = ""
    protected var loading = false
    protected var errorText: String? = null
    private var loadJob: Job? = null

    private lateinit var list: ListView
    private lateinit var adapter: Adapter
    private lateinit var crumbRow: LinearLayout
    private lateinit var crumbScroll: HorizontalScrollView
    private lateinit var largeTitleText: TextView
    private lateinit var status: FrameLayout
    private lateinit var searchField: EditText
    private lateinit var selectionBar: GlassSurface
    private lateinit var selectionRow: LinearLayout
    private var extraBox: FrameLayout? = null

    override val showsTabBar get() = !selecting && !hidesTabBar
    protected open val hidesTabBar = false
    /** Picker mode: folders open on tap even while selecting; their check circle selects them. */
    protected open val navigateInSelection = false

    override val title: CharSequence get() = titleFor(path)

    protected abstract suspend fun load(path: String): List<Item>
    protected abstract fun crumbs(path: String): List<Pair<String, String>>
    protected abstract fun onOpen(item: Item)
    protected abstract fun onItemMenu(item: Item)
    protected abstract fun selectionActions(): List<Action>
    protected abstract fun navActions(): Array<View>
    protected open fun headerExtra(): View? = null
    protected open fun titleFor(path: String): CharSequence = crumbs(path).lastOrNull()?.first ?: ""
    protected open fun emptyText(): CharSequence = str(R.string.folder_empty)
    protected open fun itemDetail(item: Item): String = buildString {
        val d = Format.date(item.modified)
        append(d)
        if (!item.isDir && item.size >= 0) { if (d.isNotEmpty()) append(" · "); append(Format.size(item.size)) }
    }

    override fun createView(): View {
        list = ListView(ctx).apply {
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(0)
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_ALWAYS
            setPadding(16.dp, 0, 16.dp, 0)
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
        }
        list.addHeaderView(buildHeader(), null, false)
        status = FrameLayout(ctx)
        list.addFooterView(status, null, false)
        adapter = Adapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> itemAt(pos)?.let(::tap) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val it = itemAt(pos) ?: return@setOnItemLongClickListener false
            if (!selecting) enterSelection(it)
            true
        }
        val root = scaffold(list)
        navBar.setTrailing(*navActions())
        buildSelectionBar(root)
        render()
        return root
    }

    private fun itemAt(listPos: Int): Item? = shown.getOrNull(listPos - list.headerViewsCount)

    private fun buildHeader(): View = ctx.vbox {
        largeTitleText = largeTitleView().apply { setPaddingRelative(4.dp, 4.dp, 4.dp, 6.dp) }
        addView(largeTitleText)
        crumbRow = ctx.hbox { setPadding(0, 0, 0, 0) }
        crumbScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(crumbRow)
        }
        addView(crumbScroll, linear(MATCH_PARENT, WRAP))
        val search = ctx.hbox {
            background = RoundDrawable(Theme.fill, 12f.dp)
            setPaddingRelative(10.dp, 0, 6.dp, 0)
            addView(ctx.icon(R.drawable.ic_search, Theme.secondaryLabel, 18.dp), linear(18.dp, 18.dp, end = 6.dp))
            searchField = ctx.field(str(R.string.search)) { q ->
                query = q
                renderList()
            }.apply { setHintTextColor(Theme.secondaryLabel); style(TextStyle.Body, Theme.label) }
            addView(searchField, linear(0, 40.dp, 1f))
            addView(ctx.icon(R.drawable.ic_close, Theme.secondaryLabel, 16.dp).apply {
                setPadding(2.dp, 2.dp, 2.dp, 2.dp)
                onTap { searchField.setText("") }
            }, linear(28.dp, 28.dp))
        }
        addView(search, linear(MATCH_PARENT, 40.dp, top = 10.dp, bottom = 12.dp))
        extraBox = FrameLayout(ctx)
        addView(extraBox, linear(MATCH_PARENT, WRAP))
    }

    private fun buildSelectionBar(root: FrameLayout) {
        selectionBar = GlassSurface(ctx, contentHost, capsule = true).apply { shadow = true; visibility = View.GONE }
        selectionRow = ctx.hbox()
        selectionBar.addView(selectionRow, frame(MATCH_PARENT, MATCH_PARENT))
        root.addView(selectionBar, frame(MATCH_PARENT, TabBar.HEIGHT, Gravity.BOTTOM, start = 16.dp, end = 16.dp))
    }

    override fun applyInsets() {
        super.applyInsets()
        if (::selectionBar.isInitialized) {
            (selectionBar.layoutParams as FrameLayout.LayoutParams).bottomMargin = nav.navInset + 10.dp
            selectionBar.requestLayout()
        }
    }

    // ------------------------------------------------------------------ navigation

    fun navigate(newPath: String, remember: Boolean = true) {
        if (remember && path.isNotEmpty() && newPath != path) history.add(path)
        path = newPath
        query = ""
        if (::searchField.isInitialized) searchField.setText("")
        if (!navigateInSelection) selected.clear()
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loading = true
        errorText = null
        if (isBuilt) render()
        val target = path
        loadJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { load(target) }
                if (target == path) items = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (target == path) { items = emptyList(); errorText = e.message ?: e.javaClass.simpleName }
            }
            loading = false
            if (isBuilt) render()
            if (isBuilt && target == path) list.setSelectionAfterHeaderView()
        }
    }

    override fun onBack(): Boolean {
        if (selecting) { exitSelection(); return true }
        if (history.isNotEmpty()) {
            navigate(history.removeAt(history.size - 1), remember = false)
            return true
        }
        return false
    }

    protected fun clearHistory() = history.clear()

    private fun tap(item: Item) {
        if (selecting && !(navigateInSelection && item.isDir)) {
            toggle(item)
            return
        }
        if (item.isDir) navigate(item.path) else onOpen(item)
    }

    private fun toggle(item: Item) {
        if (!selected.add(item.path)) selected.remove(item.path)
        updateSelectionUi()
        adapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------------ selection

    fun enterSelection(initial: Item? = null) {
        selecting = true
        selected.clear()
        initial?.let { selected += it.path }
        navBar.setLeading(navBar.textButton(str(R.string.cancel)) { exitSelection() })
        navBar.setTrailing(navBar.textButton(str(R.string.select_all)) {
            if (selected.size == shown.size) selected.clear() else { selected.clear(); shown.forEach { selected += it.path } }
            updateSelectionUi(); adapter.notifyDataSetChanged()
        })
        selectionRow.removeAllViews()
        selectionActions().forEach { a ->
            val color = if (a.style == Action.Style.DESTRUCTIVE) Theme.red else Theme.accent
            selectionRow.addView(ctx.vbox {
                gravity = Gravity.CENTER
                addView(ctx.icon(a.icon, color, 24.dp), linear(24.dp, 24.dp, gravity = Gravity.CENTER_HORIZONTAL))
                addView(ctx.text(a.label, TextStyle.Caption2, color, lines = 1).apply { gravity = Gravity.CENTER },
                    linear(WRAP, WRAP, gravity = Gravity.CENTER_HORIZONTAL, top = 3.dp))
                onTap { if (selected.isNotEmpty()) a.handler() }
            }, linear(0, MATCH_PARENT, 1f))
        }
        selectionBar.visibility = View.VISIBLE
        selectionBar.translationY = 200f.dp
        selectionBar.animate().translationY(0f).setDuration(320).setInterpolator(com.glassftp.ui.IosEase).start()
        nav.refreshChrome()
        updateSelectionUi()
        adapter.notifyDataSetChanged()
    }

    open fun exitSelection() {
        if (!selecting) return
        selecting = false
        selected.clear()
        if (nav.canGoBack(this)) navBar.setBack { nav.pop() } else navBar.setLeading()
        navBar.setTrailing(*navActions())
        navBar.setTitle(title)
        selectionBar.animate().translationY(200f.dp).setDuration(260)
            .withEndAction { selectionBar.visibility = View.GONE }.start()
        nav.refreshChrome()
        adapter.notifyDataSetChanged()
    }

    protected fun selectedItems(): List<Item> = items.filter { it.path in selected }
    protected fun selectedPaths(): List<String> = selected.toList()

    private fun updateSelectionUi() {
        navBar.setTitle(if (selected.isEmpty()) str(R.string.select_items) else str(R.string.n_selected, selected.size))
        navBar.titleAlpha(1f)
        selectionRow.alpha = if (selected.isEmpty()) 0.4f else 1f
    }

    // ------------------------------------------------------------------ rendering

    protected fun render() {
        if (!::list.isInitialized) return
        largeTitleText.text = title
        if (!selecting) navBar.setTitle(title)
        renderCrumbs()
        extraBox?.let { box ->
            box.removeAllViews()
            headerExtra()?.let { box.addView(it, frame(MATCH_PARENT, WRAP)) }
        }
        renderList()
    }

    private fun renderCrumbs() {
        crumbRow.removeAllViews()
        val cs = crumbs(path)
        cs.forEachIndexed { i, (label, p) ->
            val last = i == cs.size - 1
            if (i > 0) crumbRow.addView(ctx.icon(R.drawable.ic_chevron_forward, Theme.tertiaryLabel, 12.dp).apply { autoMirror() },
                linear(12.dp, 12.dp, start = 2.dp, end = 2.dp))
            crumbRow.addView(ctx.text(label, TextStyle.Subhead, if (last) Theme.accent else Theme.secondaryLabel, lines = 1).apply {
                typeface = Fonts.weight(if (last) 600 else 500)
                background = RoundDrawable(if (last) ColorUtil.alpha(Theme.accent, 0.14f) else 0, 0f, capsule = true, pressedOverlay = true)
                setPadding(10.dp, 6.dp, 10.dp, 6.dp)
                maxWidth = 220.dp
                if (!last) onTap { navigate(p) }
            })
        }
        crumbScroll.post { crumbScroll.fullScroll(if (crumbScroll.layoutDirection == View.LAYOUT_DIRECTION_RTL) View.FOCUS_LEFT else View.FOCUS_RIGHT) }
    }

    protected fun renderList() {
        if (!::adapter.isInitialized) return
        val prefs = App.prefs
        val q = query.trim().lowercase()
        val filtered = items.filter { (prefs.showHidden || !it.name.startsWith(".")) && (q.isEmpty() || it.name.lowercase().contains(q)) }
        val cmp: Comparator<Item> = when (prefs.sortMode) {
            SortMode.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortMode.DATE -> compareBy { it.modified }
            SortMode.SIZE -> compareBy { it.size }
            SortMode.TYPE -> compareBy<Item> { it.name.substringAfterLast('.', "").lowercase() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val ordered = if (prefs.sortDescending) cmp.reversed() else cmp
        shown = filtered.sortedWith(compareBy<Item> { !it.isDir }.then(ordered))
        adapter.notifyDataSetChanged()
        renderStatus()
    }

    private fun renderStatus() {
        status.removeAllViews()
        val box = ctx.vbox { gravity = Gravity.CENTER_HORIZONTAL; setPadding(24.dp, 36.dp, 24.dp, 24.dp) }
        when {
            loading && shown.isEmpty() -> {
                box.addView(ActivityIndicator(ctx, 26.dp), linear(WRAP, WRAP, gravity = Gravity.CENTER_HORIZONTAL))
                box.addView(ctx.text(str(R.string.loading), TextStyle.Subhead, Theme.secondaryLabel), linear(WRAP, WRAP, gravity = Gravity.CENTER_HORIZONTAL, top = 12.dp))
            }
            errorText != null -> {
                box.addView(ctx.icon(R.drawable.ic_offline, Theme.secondaryLabel, 44.dp), linear(44.dp, 44.dp, gravity = Gravity.CENTER_HORIZONTAL))
                box.addView(ctx.text(str(R.string.couldnt_load), TextStyle.Title3), linear(WRAP, WRAP, gravity = Gravity.CENTER_HORIZONTAL, top = 14.dp))
                box.addView(ctx.text(errorText ?: "", TextStyle.Subhead, Theme.secondaryLabel).apply {
                    gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
                }, linear(WRAP, WRAP, gravity = Gravity.CENTER_HORIZONTAL, top = 6.dp))
                box.addView(ctx.tintedButton(str(R.string.try_again)) { onRetry() }, linear(WRAP, WRAP, gravity = Gravity.CENTER_HORIZONTAL, top = 18.dp))
            }
            shown.isEmpty() -> {
                box.addView(ctx.icon(R.drawable.ic_folder, Theme.tertiaryLabel, 52.dp), linear(52.dp, 52.dp, gravity = Gravity.CENTER_HORIZONTAL))
                box.addView(ctx.text(if (query.isNotBlank()) str(R.string.no_results) else emptyText(), TextStyle.Headline, Theme.secondaryLabel).apply {
                    gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
                }, linear(WRAP, WRAP, gravity = Gravity.CENTER_HORIZONTAL, top = 12.dp))
            }
            else -> {
                val dirs = shown.count { it.isDir }
                box.setPadding(24.dp, 14.dp, 24.dp, 8.dp)
                box.addView(ctx.text(str(R.string.items_summary, dirs, shown.size - dirs), TextStyle.Footnote, Theme.secondaryLabel),
                    linear(WRAP, WRAP, gravity = Gravity.CENTER_HORIZONTAL))
            }
        }
        status.addView(box, frame(MATCH_PARENT, WRAP))
    }

    protected open fun onRetry() = refresh()

    // ------------------------------------------------------------------ rows

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? FileRow ?: FileRow(ctx)
            val item = shown[position]
            val pos = when {
                shown.size == 1 -> GroupedRowBackground.Position.SINGLE
                position == 0 -> GroupedRowBackground.Position.TOP
                position == shown.size - 1 -> GroupedRowBackground.Position.BOTTOM
                else -> GroupedRowBackground.Position.MIDDLE
            }
            row.bind(item, itemDetail(item), pos, selecting, item.path in selected, { toggle(item) }) { onItemMenu(item) }
            return row
        }
    }

    private class FileRow(ctx: Context) : LinearLayout(ctx) {
        private val bg = GroupedRowBackground(GroupedRowBackground.Position.MIDDLE)
        private val check = CheckCircle(ctx)
        private val iconBox = FrameLayout(ctx)
        private val name = ctx.text("", TextStyle.Body, lines = 1).apply { ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        private val detail = ctx.text("", TextStyle.Footnote, Theme.secondaryLabel, lines = 1)
        private val more = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_more)
            setColorFilter(Theme.secondaryLabel)
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
        }
        private val chevron = ctx.icon(R.drawable.ic_chevron_forward, Theme.tertiaryLabel, 14.dp).apply { autoMirror() }
        private val sep = Paint().apply { color = Theme.separator }
        private var last = false

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 62.dp
            background = bg
            setWillNotDraw(false)
            setPaddingRelative(14.dp, 8.dp, 6.dp, 8.dp)
            addView(check, linear(24.dp, 24.dp, end = 12.dp))
            addView(iconBox, linear(38.dp, 38.dp, end = 12.dp))
            addView(ctx.vbox {
                addView(name)
                addView(detail, linear(top = 3.dp))
            }, linear(0, WRAP, 1f))
            addView(chevron, linear(14.dp, 14.dp, start = 4.dp, end = 4.dp))
            addView(more, linear(40.dp, 40.dp))
        }

        fun bind(item: Item, sub: String, pos: GroupedRowBackground.Position, selecting: Boolean, isSelected: Boolean,
                 onCheck: () -> Unit, onMore: () -> Unit) {
            check.setOnClickListener { onCheck() }
            bg.position = pos
            bg.selected = selecting && isSelected
            last = pos == GroupedRowBackground.Position.BOTTOM || pos == GroupedRowBackground.Position.SINGLE
            check.visibility = if (selecting) View.VISIBLE else View.GONE
            check.checked = isSelected
            name.text = item.name
            detail.text = sub
            detail.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
            iconBox.removeAllViews()
            iconBox.addView(fileIcon(context, item.name, item.isDir, item.isLink), frame(MATCH_PARENT, MATCH_PARENT))
            chevron.visibility = if (item.isDir && !selecting) View.VISIBLE else View.GONE
            more.visibility = if (selecting) View.GONE else View.VISIBLE
            more.setOnClickListener { onMore() }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!last) {
                val start = 64.dp + if (check.visibility == View.VISIBLE) 36.dp else 0
                val h = maxOf(1f, 0.5f.dp)
                if (layoutDirection == LAYOUT_DIRECTION_RTL) canvas.drawRect(0f, height - h, (width - start).toFloat(), height.toFloat(), sep)
                else canvas.drawRect(start.toFloat(), height - h, width.toFloat(), height.toFloat(), sep)
            }
        }
    }

    companion object {
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        fun kindColor(kind: FileKind.Kind): Int = when (kind) {
            FileKind.Kind.FOLDER -> Theme.accent
            FileKind.Kind.IMAGE -> Theme.green
            FileKind.Kind.VIDEO -> Theme.purple
            FileKind.Kind.AUDIO -> Theme.pink
            FileKind.Kind.ARCHIVE -> Theme.orange
            FileKind.Kind.CODE -> Theme.indigo
            FileKind.Kind.TEXT -> Theme.gray
            FileKind.Kind.PDF -> Theme.red
            FileKind.Kind.APK -> Theme.green
            FileKind.Kind.DOC -> Theme.teal
            FileKind.Kind.OTHER -> Theme.gray
        }

        fun kindIcon(kind: FileKind.Kind): Int = when (kind) {
            FileKind.Kind.FOLDER -> R.drawable.ic_folder
            FileKind.Kind.IMAGE -> R.drawable.ic_image
            FileKind.Kind.VIDEO -> R.drawable.ic_video
            FileKind.Kind.AUDIO -> R.drawable.ic_audio
            FileKind.Kind.ARCHIVE -> R.drawable.ic_archive
            FileKind.Kind.CODE -> R.drawable.ic_code
            FileKind.Kind.TEXT -> R.drawable.ic_text
            FileKind.Kind.PDF -> R.drawable.ic_pdf
            FileKind.Kind.APK -> R.drawable.ic_apk
            FileKind.Kind.DOC -> R.drawable.ic_text
            FileKind.Kind.OTHER -> R.drawable.ic_file
        }

        fun fileIcon(ctx: Context, name: String, isDir: Boolean, isLink: Boolean = false): View {
            val kind = FileKind.of(name, isDir)
            val color = kindColor(kind)
            val box = FrameLayout(ctx)
            if (kind == FileKind.Kind.FOLDER) {
                box.addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_folder); setColorFilter(color) }, frame(MATCH_PARENT, MATCH_PARENT))
            } else {
                box.background = RoundDrawable(ColorUtil.alpha(color, 0.16f), 10f.dp)
                box.addView(ImageView(ctx).apply { setImageResource(kindIcon(kind)); setColorFilter(color) },
                    frame(22.dp, 22.dp, Gravity.CENTER))
            }
            if (isLink) {
                box.addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_link)
                    setColorFilter(0xFFFFFFFF.toInt())
                    background = RoundDrawable(Theme.gray, 0f, capsule = true)
                    setPadding(2.dp, 2.dp, 2.dp, 2.dp)
                }, frame(16.dp, 16.dp, Gravity.BOTTOM or Gravity.END))
            }
            return box
        }
    }
}
