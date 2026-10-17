package com.glassftp.ui.screens

import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import com.glassftp.App
import com.glassftp.R
import com.glassftp.core.ExistsAction
import com.glassftp.core.RemotePath
import com.glassftp.core.Site
import com.glassftp.transfer.Direction
import com.glassftp.transfer.Transfer
import com.glassftp.transfer.TransferState
import com.glassftp.ui.Action
import com.glassftp.ui.Card
import com.glassftp.ui.Fonts
import com.glassftp.ui.Navigator
import com.glassftp.ui.Screen
import com.glassftp.ui.Theme
import com.glassftp.ui.confirm
import com.glassftp.ui.dp
import com.glassftp.ui.linear
import com.glassftp.ui.vbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** FileZilla's "View/Edit": edit a downloaded copy and upload it back on save. */
class EditorScreen(nav: Navigator, private val site: Site, private val remotePath: String, private val local: File) : Screen(nav) {
    override val showsTabBar = false
    override val title: CharSequence get() = RemotePath.name(remotePath)

    private var original = ""
    private var text: String? = null
    private var saving = false
    private lateinit var editor: EditText

    override fun createView(): View {
        val content = ctx.vbox()
        val card = Card(ctx)
        editor = EditText(ctx).apply {
            background = null
            typeface = Fonts.mono
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Theme.label)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(false)
            setPadding(14.dp, 14.dp, 14.dp, 14.dp)
            minHeight = 360.dp
            textDirection = View.TEXT_DIRECTION_LOCALE
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        card.addRow(editor)
        content.addView(card, linear(start = 12.dp, end = 12.dp, top = 8.dp))
        val scroll = ScrollView(ctx).apply { addView(content); isFillViewport = true }
        val root = scaffold(scroll, largeTitle = false)
        navBar.setTrailing(navBar.textButton(str(R.string.save), prominent = true) { save() })
        if (text == null) {
            scope.launch {
                val t = withContext(Dispatchers.IO) { runCatching { local.readText() }.getOrDefault("") }
                original = t; text = t
                editor.setText(t)
            }
        } else editor.setText(text)
        return root
    }

    override fun onDisappear() {
        if (::editor.isInitialized) text = editor.text.toString()
    }

    override fun onBack(): Boolean {
        val current = editor.text.toString()
        if (current == original || saving) return false
        scope.launch {
            if (overlay.confirm(str(R.string.discard_title), str(R.string.discard_msg), str(R.string.discard), str(R.string.keep_editing), destructive = true)) {
                original = current
                nav.pop()
            }
        }
        return true
    }

    private fun save() {
        if (saving) return
        saving = true
        val current = editor.text.toString()
        scope.launch {
            withContext(Dispatchers.IO) { local.writeText(current) }
            val t = Transfer(site, Direction.UPLOAD, remotePath, local, null, local.name, existsAction = ExistsAction.OVERWRITE, silent = true)
            t.total = local.length()
            App.transfers.enqueue(t) { done ->
                saving = false
                if (done.state == TransferState.DONE) {
                    original = current
                    overlay.toast(str(R.string.saved_to_server), R.drawable.ic_check, Theme.green)
                } else {
                    overlay.alert(str(R.string.operation_failed), done.error ?: "", listOf(Action(str(R.string.ok), Action.Style.PREFERRED)))
                }
            }
        }
    }
}
