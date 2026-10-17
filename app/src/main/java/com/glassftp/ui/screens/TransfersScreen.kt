package com.glassftp.ui.screens

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import com.glassftp.App
import com.glassftp.R
import com.glassftp.core.Format
import com.glassftp.transfer.Direction
import com.glassftp.transfer.Transfer
import com.glassftp.transfer.TransferState
import com.glassftp.ui.Action
import com.glassftp.ui.GroupedRowBackground
import com.glassftp.ui.Navigator
import com.glassftp.ui.ProgressLine
import com.glassftp.ui.Screen
import com.glassftp.ui.TextStyle
import com.glassftp.ui.Theme
import com.glassftp.ui.dp
import com.glassftp.ui.frame
import com.glassftp.ui.icon
import com.glassftp.ui.linear
import com.glassftp.ui.sectionHeader
import com.glassftp.ui.text
import com.glassftp.ui.tile
import com.glassftp.ui.vbox

/** FileZilla's transfer queue: queued / running / finished, with progress, speed and ETA. */
class TransfersScreen(nav: Navigator) : Screen(nav) {
    override val title: CharSequence get() = str(R.string.tab_transfers)

    private sealed class Row {
        class Header(val text: String) : Row()
        class Item(val t: Transfer, val pos: GroupedRowBackground.Position) : Row()
    }

    private var rows: List<Row> = emptyList()
    private lateinit var list: ListView
    private lateinit var adapter: BaseAdapter
    private lateinit var empty: View
    private val listener = { reload() }

    override fun createView(): View {
        list = ListView(ctx).apply {
            divider = null
            selector = ColorDrawable(0)
            setPadding(16.dp, 0, 16.dp, 0)
        }
        list.addHeaderView(largeTitleView().apply { setPaddingRelative(4.dp, 4.dp, 4.dp, 6.dp) }, null, false)
        empty = ctx.vbox {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24.dp, 80.dp, 24.dp, 24.dp)
            addView(ctx.icon(R.drawable.ic_transfers, Theme.tertiaryLabel, 56.dp), linear(56.dp, 56.dp, gravity = Gravity.CENTER_HORIZONTAL))
            addView(ctx.text(str(R.string.no_transfers), TextStyle.Title3).apply { gravity = Gravity.CENTER },
                linear(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, gravity = Gravity.CENTER_HORIZONTAL, top = 14.dp))
            addView(ctx.text(str(R.string.no_transfers_hint), TextStyle.Subhead, Theme.secondaryLabel).apply {
                gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
            }, linear(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, gravity = Gravity.CENTER_HORIZONTAL, top = 6.dp))
        }
        val footer = FrameLayout(ctx).apply { addView(empty, frame()) }
        list.addFooterView(footer, null, false)
        adapter = Adapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            (rows.getOrNull(pos - list.headerViewsCount) as? Row.Item)?.let { tap(it.t) }
        }
        val root = scaffold(list)
        navBar.setTrailing(navBar.group(R.drawable.ic_more to { _ -> menu() }))
        reload()
        return root
    }

    override fun onAppear() {
        App.transfers.addListener(listener)
        reload()
    }

    override fun onDisappear() = App.transfers.removeListener(listener)

    override fun onDestroy() {
        App.transfers.removeListener(listener)
        super.onDestroy()
    }

    private fun reload() {
        val all = App.transfers.snapshot().filterNot { it.isFolder && it.state.finished && it.note?.startsWith("queued") == true }
        val active = all.filter { !it.state.finished }
        val done = all.filter { it.state.finished }.sortedByDescending { it.finishedAt }
        val out = mutableListOf<Row>()
        fun section(title: String, items: List<Transfer>) {
            if (items.isEmpty()) return
            out += Row.Header(title)
            items.forEachIndexed { i, t ->
                out += Row.Item(t, when {
                    items.size == 1 -> GroupedRowBackground.Position.SINGLE
                    i == 0 -> GroupedRowBackground.Position.TOP
                    i == items.size - 1 -> GroupedRowBackground.Position.BOTTOM
                    else -> GroupedRowBackground.Position.MIDDLE
                })
            }
        }
        section(str(R.string.in_progress, active.size), active)
        section(str(R.string.finished), done)
        rows = out
        if (isBuilt) {
            empty.visibility = if (out.isEmpty()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
        }
    }

    private fun tap(t: Transfer) {
        val actions = mutableListOf<Action>()
        if (t.state == TransferState.DONE && t.direction == Direction.DOWNLOAD && !t.isFolder) {
            t.localFile?.let { f ->
                actions += Action(str(R.string.open), icon = R.drawable.ic_open) { LocalScreen.openExternally(nav, f, false) }
                actions += Action(str(R.string.share), icon = R.drawable.ic_share) { LocalScreen.openExternally(nav, f, true) }
            }
        }
        if (t.state == TransferState.FAILED || t.state == TransferState.CANCELED) {
            actions += Action(str(R.string.retry), icon = R.drawable.ic_refresh) { App.transfers.retry(t) }
        }
        if (!t.state.finished) actions += Action(str(R.string.cancel_transfer), Action.Style.DESTRUCTIVE, R.drawable.ic_close) { App.transfers.cancel(t) }
        else actions += Action(str(R.string.remove_from_list), Action.Style.DESTRUCTIVE, R.drawable.ic_trash) { App.transfers.remove(t) }
        val where = if (t.direction == Direction.DOWNLOAD) t.localFile?.absolutePath else "${t.site.displayName}:${t.remotePath}"
        overlay.sheet(t.name, listOfNotNull(where, t.error).joinToString("\n"), actions)
    }

    private fun menu() {
        overlay.sheet(null, null, listOf(
            Action(str(R.string.clear_finished), icon = R.drawable.ic_check) { App.transfers.clearFinished() },
            Action(str(R.string.retry_failed), icon = R.drawable.ic_refresh) { App.transfers.retryFailed() },
            Action(str(R.string.cancel_all), Action.Style.DESTRUCTIVE, R.drawable.ic_close) { App.transfers.cancelAll() },
        ))
    }

    private fun status(t: Transfer): String = when (t.state) {
        TransferState.QUEUED -> str(R.string.st_waiting) + if (t.total > 0) " · ${Format.size(t.total)}" else ""
        TransferState.RUNNING -> when {
            t.isFolder -> str(R.string.st_scanning)
            else -> buildString {
                append(str(R.string.st_progress, Format.size(t.done), if (t.total >= 0) Format.size(t.total) else "?"))
                if (t.speed > 0) append(" · ").append(Format.speed(t.speed))
                if (t.etaSeconds >= 0) append(" · ").append(str(R.string.st_left, Format.duration(t.etaSeconds)))
            }
        }
        TransferState.DONE -> when {
            t.isFolder -> str(R.string.st_folder_queued, t.note?.substringAfter(':')?.toIntOrNull() ?: 0)
            else -> str(if (t.direction == Direction.DOWNLOAD) R.string.st_downloaded else R.string.st_uploaded) + " · " + Format.size(t.total)
        }
        TransferState.SKIPPED -> str(if (t.note == "complete") R.string.st_complete else R.string.st_skipped)
        TransferState.FAILED -> t.error ?: str(R.string.st_failed)
        TransferState.CANCELED -> str(R.string.st_cancelled)
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (rows[p] is Row.Header) 0 else 1
        override fun isEnabled(p: Int) = rows[p] is Row.Item

        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View = when (val r = rows[p]) {
            is Row.Header -> ctx.sectionHeader(r.text).apply { setPaddingRelative(20.dp, 18.dp, 20.dp, 7.dp) }
            is Row.Item -> (convert as? TransferRow ?: TransferRow(ctx)).also { it.bind(r.t, r.pos, status(r.t)) }
        }
    }

    private inner class TransferRow(ctx: Context) : LinearLayout(ctx) {
        private val bg = GroupedRowBackground(GroupedRowBackground.Position.MIDDLE)
        private val iconBox = FrameLayout(ctx)
        private val name = ctx.text("", TextStyle.Body, lines = 1).apply { ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        private val progress = ProgressLine(ctx)
        private val detail = ctx.text("", TextStyle.Footnote, Theme.secondaryLabel, lines = 2)
        private val action = ImageView(ctx).apply { setPadding(9.dp, 9.dp, 9.dp, 9.dp) }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            minimumHeight = 66.dp
            setPaddingRelative(14.dp, 10.dp, 6.dp, 10.dp)
            addView(iconBox, linear(34.dp, 34.dp, end = 12.dp))
            addView(ctx.vbox {
                addView(name)
                addView(progress, linear(top = 6.dp))
                addView(detail, linear(top = 4.dp))
            }, linear(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(action, linear(40.dp, 40.dp, start = 4.dp))
        }

        fun bind(t: Transfer, pos: GroupedRowBackground.Position, sub: String) {
            bg.position = pos
            name.text = t.name
            detail.text = sub
            iconBox.removeAllViews()
            val color = when (t.state) {
                TransferState.FAILED -> Theme.red
                TransferState.CANCELED, TransferState.SKIPPED -> Theme.gray
                TransferState.DONE -> Theme.green
                else -> if (t.direction == Direction.DOWNLOAD) Theme.accent else Theme.indigo
            }
            val ic = when {
                t.isFolder -> R.drawable.ic_folder
                t.direction == Direction.DOWNLOAD -> R.drawable.ic_arrow_down
                else -> R.drawable.ic_arrow_up
            }
            iconBox.addView(context.tile(ic, color, 34.dp), frame(34.dp, 34.dp))
            val running = t.state == TransferState.RUNNING && !t.isFolder
            progress.visibility = if (running || t.state == TransferState.QUEUED) View.VISIBLE else View.GONE
            progress.progress = t.progress.toFloat()
            progress.color = if (t.direction == Direction.DOWNLOAD) Theme.accent else Theme.indigo
            detail.setTextColor(if (t.state == TransferState.FAILED) Theme.red else Theme.secondaryLabel)
            when {
                !t.state.finished -> {
                    action.setImageResource(R.drawable.ic_close)
                    action.setColorFilter(Theme.secondaryLabel)
                    action.visibility = View.VISIBLE
                    action.setOnClickListener { App.transfers.cancel(t) }
                }
                t.state == TransferState.FAILED || t.state == TransferState.CANCELED -> {
                    action.setImageResource(R.drawable.ic_refresh)
                    action.setColorFilter(Theme.accent)
                    action.visibility = View.VISIBLE
                    action.setOnClickListener { App.transfers.retry(t) }
                }
                else -> action.visibility = View.INVISIBLE
            }
        }
    }
}
