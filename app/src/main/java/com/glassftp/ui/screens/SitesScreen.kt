package com.glassftp.ui.screens

import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.glassftp.App
import com.glassftp.R
import com.glassftp.core.Protocol
import com.glassftp.core.Site
import com.glassftp.ui.Action
import com.glassftp.ui.Card
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
import com.glassftp.ui.filledButton
import com.glassftp.ui.hbox
import com.glassftp.ui.icon
import com.glassftp.ui.linear
import com.glassftp.ui.onTap
import com.glassftp.ui.plainButton
import com.glassftp.ui.row
import com.glassftp.ui.sectionFooter
import com.glassftp.ui.sectionHeader
import com.glassftp.ui.text
import com.glassftp.ui.tile
import com.glassftp.ui.vbox
import kotlinx.coroutines.launch

/** Quick Connect bar + Site Manager list. */
class SitesScreen(nav: Navigator) : Screen(nav) {
    override val title: CharSequence get() = str(R.string.tab_servers)

    private var qProtocol = Protocol.FTP
    private var qHost = ""
    private var qPort = ""
    private var qUser = ""
    private var qPass = ""
    private lateinit var sitesBox: LinearLayout
    private val storeListener = { renderSites() }

    override fun createView(): View {
        val content = ctx.vbox()
        content.addView(largeTitleView())

        content.addView(ctx.sectionHeader(str(R.string.quick_connect)))
        val quick = Card(ctx)
        val portField = ctx.field(qProtocol.defaultPort.toString(), qPort, InputType.TYPE_CLASS_NUMBER) { qPort = it }
        quick.addRow(ctx.hbox {
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            addView(Segmented(ctx, Protocol.entries.map { it.label }, qProtocol.ordinal) { i ->
                val old = qProtocol
                qProtocol = Protocol.entries[i]
                portField.hint = qProtocol.defaultPort.toString()
                if (qPort.trim() == old.defaultPort.toString()) portField.setText("")
            }, linear(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        quick.addRow(ctx.fieldRow(str(R.string.host), ctx.field("ftp.example.com", qHost,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { qHost = it }))
        quick.addRow(ctx.fieldRow(str(R.string.port), portField))
        quick.addRow(ctx.fieldRow(str(R.string.username), ctx.field(str(R.string.optional), qUser,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) { qUser = it }))
        quick.addRow(ctx.fieldRow(str(R.string.password), ctx.field(str(R.string.optional), qPass,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) { qPass = it }))
        quick.addRow(ctx.vbox {
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            addView(ctx.filledButton(str(R.string.connect)) { quickConnect() })
        }, inset = 0)
        content.addCard(quick)
        content.addView(ctx.hbox {
            addView(ctx.sectionFooter(str(R.string.quick_hint)), linear(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(ctx.plainButton(str(R.string.save), st = TextStyle.Footnote, weight = 600) { saveQuick() }, linear(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, end = 24.dp))
        })

        sitesBox = ctx.vbox()
        content.addView(sitesBox)
        renderSites()

        val scroll = ScrollView(ctx).apply { addView(content); isVerticalScrollBarEnabled = false }
        val root = scaffold(scroll)
        navBar.setTrailing(navBar.group(R.drawable.ic_plus to { _ -> nav.push(SiteEditScreen(nav, null)) }))
        return root
    }

    override fun onAppear() {
        App.sites.addListener(storeListener)
        renderSites()
    }

    override fun onDisappear() = App.sites.removeListener(storeListener)

    override fun onDestroy() {
        App.sites.removeListener(storeListener)
        super.onDestroy()
    }

    private fun quickSite(): Site? {
        var host = qHost.trim()
        var protocol = qProtocol
        var user = qUser.trim()
        var port = qPort.trim().toIntOrNull()
        // Accept URLs like sftp://user@host:2222/path
        if (host.contains("://")) {
            val u = runCatching { Uri.parse(host) }.getOrNull()
            if (u != null && !u.host.isNullOrEmpty()) {
                protocol = when (u.scheme?.lowercase()) {
                    "sftp", "ssh" -> Protocol.SFTP
                    "ftps" -> Protocol.FTPS
                    "ftpes" -> Protocol.FTPES
                    else -> Protocol.FTP
                }
                host = u.host!!
                if (u.port > 0) port = u.port
                u.userInfo?.substringBefore(':')?.takeIf { it.isNotEmpty() }?.let { user = it }
            }
        }
        if (host.isEmpty()) {
            overlay.toast(str(R.string.enter_host), R.drawable.ic_info, Theme.orange)
            return null
        }
        return Site(name = host, protocol = protocol, host = host, port = port ?: protocol.defaultPort,
            user = user, password = qPass, anonymous = user.isEmpty() && protocol.isFtp, savePassword = true)
    }

    private fun quickConnect() {
        val s = quickSite() ?: return
        nav.push(RemoteScreen(nav, s))
    }

    private fun saveQuick() {
        val s = quickSite() ?: return
        App.sites.save(s)
        overlay.toast(str(R.string.site_saved), R.drawable.ic_check, Theme.green)
    }

    private fun protocolColor(p: Protocol) = when (p) {
        Protocol.FTP -> Theme.accent
        Protocol.FTPES -> Theme.green
        Protocol.FTPS -> Theme.teal
        Protocol.SFTP -> Theme.purple
    }

    private fun renderSites() {
        if (!isBuilt || !::sitesBox.isInitialized) return
        sitesBox.removeAllViews()
        val sites = App.sites.all().sortedWith(compareByDescending<Site> { it.lastUsed }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
        sitesBox.addView(ctx.sectionHeader(str(R.string.my_servers)))
        val card = Card(ctx).apply { separatorInset = 60.dp }
        if (sites.isEmpty()) {
            card.addRow(ctx.vbox {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(20.dp, 26.dp, 20.dp, 22.dp)
                addView(ctx.icon(R.drawable.ic_server, Theme.tertiaryLabel, 44.dp), linear(44.dp, 44.dp, gravity = Gravity.CENTER_HORIZONTAL))
                addView(ctx.text(str(R.string.no_servers), TextStyle.Headline).apply { gravity = Gravity.CENTER },
                    linear(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, gravity = Gravity.CENTER_HORIZONTAL, top = 10.dp))
                addView(ctx.text(str(R.string.no_servers_hint), TextStyle.Subhead, Theme.secondaryLabel).apply {
                    gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
                }, linear(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, gravity = Gravity.CENTER_HORIZONTAL, top = 4.dp))
                addView(ctx.plainButton(str(R.string.add_server), weight = 600) { nav.push(SiteEditScreen(nav, null)) },
                    linear(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, gravity = Gravity.CENTER_HORIZONTAL, top = 6.dp))
            })
        } else {
            sites.forEach { s ->
                val info = ctx.icon(R.drawable.ic_info, Theme.accent, 22.dp).apply {
                    setPadding(0, 0, 0, 0)
                    onTap { nav.push(SiteEditScreen(nav, s)) }
                }
                val r = ctx.row(
                    title = s.displayName,
                    subtitle = "${s.protocol.label} · ${s.subtitle}",
                    leading = ctx.tile(R.drawable.ic_server, protocolColor(s.protocol), 32.dp),
                    trailing = info,
                ) { nav.push(RemoteScreen(nav, s)) }
                r.setOnLongClickListener { siteMenu(s); true }
                card.addRow(r)
            }
        }
        sitesBox.addCard(card)
        sitesBox.addView(ctx.sectionFooter(str(R.string.import_hint)))
    }

    private fun siteMenu(s: Site) {
        overlay.sheet(s.displayName, s.subtitle, listOf(
            Action(str(R.string.connect), icon = R.drawable.ic_bolt) { nav.push(RemoteScreen(nav, s)) },
            Action(str(R.string.edit), icon = R.drawable.ic_edit) { nav.push(SiteEditScreen(nav, s)) },
            Action(str(R.string.duplicate), icon = R.drawable.ic_copy) {
                App.sites.save(s.copy(id = java.util.UUID.randomUUID().toString(), name = s.displayName + " " + str(R.string.copy_suffix), lastUsed = 0))
            },
            Action(str(R.string.delete), Action.Style.DESTRUCTIVE, R.drawable.ic_trash) {
                scope.launch {
                    if (overlay.confirm(str(R.string.delete_site_title), str(R.string.delete_one_msg, s.displayName),
                            str(R.string.delete), str(R.string.cancel), destructive = true)) App.sites.delete(s.id)
                }
            },
        ))
    }
}
