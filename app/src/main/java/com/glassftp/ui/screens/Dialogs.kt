package com.glassftp.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.glassftp.App
import com.glassftp.R
import com.glassftp.core.SortMode
import com.glassftp.ui.Action
import com.glassftp.ui.CheckCircle
import com.glassftp.ui.Fonts
import com.glassftp.ui.Screen
import com.glassftp.ui.TextStyle
import com.glassftp.ui.Theme
import com.glassftp.ui.dp
import com.glassftp.ui.hbox
import com.glassftp.ui.linear
import com.glassftp.ui.onTap
import com.glassftp.ui.text
import com.glassftp.ui.vbox

fun showSortMenu(screen: Screen, onChange: () -> Unit) {
    val p = App.prefs
    fun pick(m: SortMode) {
        if (p.sortMode == m) p.sortDescending = !p.sortDescending else { p.sortMode = m; p.sortDescending = m == SortMode.DATE || m == SortMode.SIZE }
        onChange()
    }
    fun icon(m: SortMode) = if (p.sortMode == m) (if (p.sortDescending) R.drawable.ic_arrow_down else R.drawable.ic_arrow_up) else 0
    screen.overlay.sheet(screen.str(R.string.sort_by), screen.str(R.string.sort_hint), listOf(
        Action(screen.str(R.string.sort_name), icon = icon(SortMode.NAME)) { pick(SortMode.NAME) },
        Action(screen.str(R.string.sort_date), icon = icon(SortMode.DATE)) { pick(SortMode.DATE) },
        Action(screen.str(R.string.sort_size), icon = icon(SortMode.SIZE)) { pick(SortMode.SIZE) },
        Action(screen.str(R.string.sort_type), icon = icon(SortMode.TYPE)) { pick(SortMode.TYPE) },
    ))
}

/** chmod editor: owner/group/public × read/write/execute, like FileZilla's "File permissions" dialog. */
object PermissionsDialog {
    fun show(screen: Screen, name: String, current: String, onApply: (Int) -> Unit) {
        val ctx = screen.ctx
        var mode = "644".toInt(8)
        if (current.length >= 10) {
            mode = 0
            for (i in 1..9) if (current[i] != '-') mode = mode or (1 shl (9 - i))
        }
        val octal: TextView = ctx.text("", TextStyle.Title2).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            typeface = Fonts.mono
        }
        fun refresh() { octal.text = "%03o".format(mode) }
        val grid = ctx.vbox {
            addView(ctx.hbox {
                addView(View(ctx), linear(0, 1, 1.4f))
                listOf(R.string.perm_read, R.string.perm_write, R.string.perm_exec).forEach { id ->
                    addView(ctx.text(screen.str(id), TextStyle.Footnote, Theme.secondaryLabel).apply { gravity = Gravity.CENTER }, linear(0, -2, 1f))
                }
            })
            listOf(R.string.perm_owner to 6, R.string.perm_group to 3, R.string.perm_public to 0).forEach { (label, shift) ->
                addView(ctx.hbox {
                    setPadding(0, 10.dp, 0, 0)
                    addView(ctx.text(screen.str(label), TextStyle.Subhead), linear(0, -2, 1.4f))
                    for (bit in 2 downTo 0) {
                        val mask = 1 shl (shift + bit)
                        val c = CheckCircle(ctx).apply { checked = mode and mask != 0 }
                        addView(android.widget.FrameLayout(ctx).apply {
                            addView(c, android.widget.FrameLayout.LayoutParams(24.dp, 24.dp, Gravity.CENTER))
                            onTap { mode = mode xor mask; c.checked = mode and mask != 0; refresh() }
                        }, linear(0, 36.dp, 1f))
                    }
                })
            }
            addView(octal, linear(top = 14.dp))
        }
        refresh()
        screen.overlay.alert(screen.str(R.string.permissions), name, listOf(
            Action(screen.str(R.string.cancel), Action.Style.CANCEL),
            Action(screen.str(R.string.apply), Action.Style.PREFERRED) { onApply(mode) },
        ), content = grid)
    }
}
