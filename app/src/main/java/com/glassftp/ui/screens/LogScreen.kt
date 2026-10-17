package com.glassftp.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import com.glassftp.R
import com.glassftp.core.AppLog
import com.glassftp.ui.Fonts
import com.glassftp.ui.Navigator
import com.glassftp.ui.Screen
import com.glassftp.ui.Theme
import com.glassftp.ui.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** FileZilla's message log: status, commands (→), replies (←) and errors. */
class LogScreen(nav: Navigator) : Screen(nav) {
    override val showsTabBar = false
    override val title: CharSequence get() = str(R.string.message_log)

    private var entries: List<AppLog.Entry> = emptyList()
    private lateinit var list: ListView
    private lateinit var adapter: BaseAdapter
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val listener = { reload() }

    override fun createView(): View {
        list = ListView(ctx).apply {
            divider = null
            selector = ColorDrawable(0)
            setPadding(12.dp, 0, 12.dp, 0)
            isStackFromBottom = false
            transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
        }
        list.addHeaderView(largeTitleView().apply { setPaddingRelative(8.dp, 4.dp, 8.dp, 10.dp) }, null, false)
        adapter = object : BaseAdapter() {
            override fun getCount() = entries.size
            override fun getItem(p: Int) = entries[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
                val tv = convert as? TextView ?: TextView(ctx).apply {
                    typeface = Fonts.mono
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(12.dp, 3.dp, 12.dp, 3.dp)
                    textDirection = View.TEXT_DIRECTION_LTR
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                }
                val e = entries[p]
                val arrow = when (e.kind) {
                    AppLog.Kind.COMMAND -> "→ "
                    AppLog.Kind.REPLY -> "← "
                    AppLog.Kind.ERROR -> "✕ "
                    AppLog.Kind.STATUS -> "• "
                }
                tv.text = "${fmt.format(Date(e.time))} ${e.source}  $arrow${e.text}"
                tv.setTextColor(when (e.kind) {
                    AppLog.Kind.COMMAND -> Theme.accent
                    AppLog.Kind.REPLY -> Theme.green
                    AppLog.Kind.ERROR -> Theme.red
                    AppLog.Kind.STATUS -> Theme.label
                })
                return tv
            }
        }
        list.adapter = adapter
        val root = scaffold(list)
        navBar.setTrailing(navBar.group(
            R.drawable.ic_copy to { _ -> copy() },
            R.drawable.ic_trash to { _ -> AppLog.clear() },
        ))
        reload()
        return root
    }

    override fun onAppear() {
        AppLog.addListener(listener)
        reload()
    }

    override fun onDisappear() = AppLog.removeListener(listener)

    override fun onDestroy() {
        AppLog.removeListener(listener)
        super.onDestroy()
    }

    private fun reload() {
        entries = AppLog.snapshot()
        if (isBuilt) adapter.notifyDataSetChanged()
    }

    private fun copy() {
        val text = entries.joinToString("\n") { "${fmt.format(Date(it.time))} [${it.kind}] ${it.source}: ${it.text}" }
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("log", text))
        overlay.toast(str(R.string.copied), R.drawable.ic_copy)
    }
}
