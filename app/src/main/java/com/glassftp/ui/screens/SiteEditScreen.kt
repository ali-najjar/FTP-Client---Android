package com.glassftp.ui.screens

import android.app.Activity
import android.content.Intent
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.glassftp.App
import com.glassftp.R
import com.glassftp.core.Protocol
import com.glassftp.core.Site
import com.glassftp.ui.Card
import com.glassftp.ui.IosSwitch
import com.glassftp.ui.Navigator
import com.glassftp.ui.Screen
import com.glassftp.ui.Segmented
import com.glassftp.ui.TextStyle
import com.glassftp.ui.Theme
import com.glassftp.ui.addCard
import com.glassftp.ui.confirm
import com.glassftp.ui.dp
import com.glassftp.ui.field
import com.glassftp.ui.fieldRow
import com.glassftp.ui.hbox
import com.glassftp.ui.icon
import com.glassftp.ui.linear
import com.glassftp.ui.onTap
import com.glassftp.ui.row
import com.glassftp.ui.sectionFooter
import com.glassftp.ui.sectionHeader
import com.glassftp.ui.text
import com.glassftp.ui.tintedButton
import com.glassftp.ui.vbox
import kotlinx.coroutines.launch

/** Site Manager entry editor. */
class SiteEditScreen(nav: Navigator, private val original: Site?) : Screen(nav) {
    override val showsTabBar = false
    override val title: CharSequence get() = if (original == null) str(R.string.new_server) else str(R.string.edit_server)

    private var s = original ?: Site()
    private val ftpOnly = mutableListOf<View>()
    private val sftpOnly = mutableListOf<View>()
    private lateinit var portField: EditText
    private lateinit var protocolFooter: TextView
    private lateinit var keyRow: LinearLayout

    override fun createView(): View {
        ftpOnly.clear(); sftpOnly.clear()
        val content = ctx.vbox()
        content.addView(largeTitleView())

        content.addView(ctx.sectionHeader(str(R.string.general)))
        val general = Card(ctx)
        general.addRow(ctx.fieldRow(str(R.string.name), ctx.field(str(R.string.optional), s.name) { s = s.copy(name = it) }))
        general.addRow(ctx.hbox {
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            addView(Segmented(ctx, Protocol.entries.map { it.label }, s.protocol.ordinal) { i -> setProtocol(Protocol.entries[i]) },
                linear(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        general.addRow(ctx.fieldRow(str(R.string.host), ctx.field("ftp.example.com", s.host,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { s = s.copy(host = it.trim()) }))
        portField = ctx.field(s.protocol.defaultPort.toString(), if (s.port == s.protocol.defaultPort) "" else s.port.toString(),
            InputType.TYPE_CLASS_NUMBER) { s = s.copy(port = it.toIntOrNull() ?: s.protocol.defaultPort) }
        general.addRow(ctx.fieldRow(str(R.string.port), portField))
        content.addCard(general)
        protocolFooter = ctx.sectionFooter("")
        content.addView(protocolFooter)

        content.addView(ctx.sectionHeader(str(R.string.login)))
        val login = Card(ctx)
        login.addRow(ctx.fieldRow(str(R.string.username), ctx.field(str(R.string.username), s.user,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) { s = s.copy(user = it.trim()) }))
        val pass = ctx.field(str(R.string.password), s.password, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) { s = s.copy(password = it) }
        login.addRow(ctx.hbox {
            addView(ctx.fieldRow(str(R.string.password), pass), linear(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(ctx.icon(R.drawable.ic_eye, Theme.secondaryLabel, 22.dp).apply {
                onTap {
                    val visible = pass.inputType and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != 0
                    pass.inputType = InputType.TYPE_CLASS_TEXT or if (visible) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    pass.typeface = com.glassftp.ui.Fonts.weight(400)
                    pass.setSelection(pass.text.length)
                }
            }, linear(22.dp, 22.dp, end = 16.dp))
        })
        val anon = ctx.row(str(R.string.anonymous), trailing = IosSwitch(ctx, s.anonymous) { v -> s = s.copy(anonymous = v) })
        login.addRow(anon); ftpOnly += anon; ftpOnly += anon.prevSeparator()
        login.addRow(ctx.row(str(R.string.save_password), trailing = IosSwitch(ctx, s.savePassword) { v -> s = s.copy(savePassword = v) }))
        keyRow = ctx.row(str(R.string.private_key), subtitle = keyLabel(), chevron = true) { keyMenu() }
        login.addRow(keyRow); sftpOnly += keyRow; sftpOnly += keyRow.prevSeparator()
        val phrase = ctx.fieldRow(str(R.string.passphrase), ctx.field(str(R.string.optional), s.keyPassphrase,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) { s = s.copy(keyPassphrase = it) })
        login.addRow(phrase); sftpOnly += phrase; sftpOnly += phrase.prevSeparator()
        content.addCard(login)
        content.addView(ctx.sectionFooter(str(R.string.password_footer)))

        content.addView(ctx.sectionHeader(str(R.string.advanced)))
        val adv = Card(ctx)
        val passive = ctx.row(str(R.string.passive_mode), subtitle = str(R.string.passive_hint), trailing = IosSwitch(ctx, s.passive) { v -> s = s.copy(passive = v) })
        adv.addRow(passive); ftpOnly += passive
        val encLabels = listOf(str(R.string.enc_auto), "UTF-8", "Win-1256", "Latin-1")
        val encRow = ctx.vbox {
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            addView(ctx.text(str(R.string.encoding), TextStyle.Body))
            addView(Segmented(ctx, encLabels, Site.ENCODINGS.indexOf(s.encoding).coerceAtLeast(0)) { i ->
                s = s.copy(encoding = Site.ENCODINGS[i])
            }, linear(top = 10.dp))
        }
        adv.addRow(encRow)
        ftpOnly += encRow.prevSeparator()
        adv.addRow(ctx.fieldRow(str(R.string.remote_dir), ctx.field("/", s.remoteDir,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { s = s.copy(remoteDir = it.trim()) }))
        content.addCard(adv)
        content.addView(ctx.sectionFooter(str(R.string.encoding_footer)))

        if (original != null) {
            content.addView(ctx.tintedButton(str(R.string.delete_server), Theme.red) { deleteSite() },
                linear(start = 16.dp, end = 16.dp, top = 28.dp))
        }

        val scroll = ScrollView(ctx).apply { addView(content); isVerticalScrollBarEnabled = false }
        val root = scaffold(scroll)
        navBar.setTrailing(navBar.textButton(str(R.string.save), prominent = true) { save() })
        applyProtocolVisibility()
        return root
    }

    private fun View.prevSeparator(): View {
        val parent = parent as? LinearLayout ?: return View(context)
        val i = parent.indexOfChild(this)
        return if (i > 0) parent.getChildAt(i - 1) else View(context)
    }

    private fun setProtocol(p: Protocol) {
        val wasDefault = s.port == s.protocol.defaultPort
        s = s.copy(protocol = p, port = if (wasDefault) p.defaultPort else s.port)
        portField.hint = p.defaultPort.toString()
        applyProtocolVisibility()
    }

    private fun applyProtocolVisibility() {
        val sftp = s.protocol == Protocol.SFTP
        ftpOnly.forEach { it.visibility = if (sftp) View.GONE else View.VISIBLE }
        sftpOnly.forEach { it.visibility = if (sftp) View.VISIBLE else View.GONE }
        protocolFooter.text = str(when (s.protocol) {
            Protocol.FTP -> R.string.proto_ftp
            Protocol.FTPES -> R.string.proto_ftpes
            Protocol.FTPS -> R.string.proto_ftps
            Protocol.SFTP -> R.string.proto_sftp
        })
    }

    private fun keyLabel() = if (s.privateKey.isBlank()) str(R.string.none) else str(R.string.key_loaded)

    private fun keyMenu() {
        val actions = mutableListOf(com.glassftp.ui.Action(str(R.string.choose_key_file), icon = R.drawable.ic_key) { pickKey() })
        if (s.privateKey.isNotBlank()) actions += com.glassftp.ui.Action(str(R.string.remove_key), com.glassftp.ui.Action.Style.DESTRUCTIVE, R.drawable.ic_trash) {
            s = s.copy(privateKey = ""); updateKeyRow()
        }
        overlay.sheet(str(R.string.private_key), str(R.string.key_hint), actions)
    }

    private fun pickKey() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        nav.activity.launch(i) { code, data ->
            val uri = data?.data
            if (code != Activity.RESULT_OK || uri == null) return@launch
            val text = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            if (text == null || !text.contains("PRIVATE KEY")) {
                overlay.toast(str(R.string.invalid_key), R.drawable.ic_close, Theme.red)
                return@launch
            }
            s = s.copy(privateKey = text)
            updateKeyRow()
        }
    }

    private fun updateKeyRow() {
        ((keyRow.getChildAt(0) as? LinearLayout)?.getChildAt(1) as? TextView)?.text = keyLabel()
    }

    private fun save() {
        if (s.host.isBlank()) {
            overlay.toast(str(R.string.enter_host), R.drawable.ic_info, Theme.orange)
            return
        }
        App.sites.save(s)
        nav.pop()
    }

    private fun deleteSite() {
        val o = original ?: return
        scope.launch {
            if (overlay.confirm(str(R.string.delete_site_title), str(R.string.delete_one_msg, o.displayName),
                    str(R.string.delete), str(R.string.cancel), destructive = true)) {
                App.sites.delete(o.id)
                nav.pop()
            }
        }
    }
}
