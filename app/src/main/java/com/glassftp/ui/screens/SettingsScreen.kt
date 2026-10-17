package com.glassftp.ui.screens

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.glassftp.App
import com.glassftp.BuildInfo
import com.glassftp.R
import com.glassftp.core.ExistsAction
import com.glassftp.core.FileZillaImport
import com.glassftp.ui.Action
import com.glassftp.ui.Card
import com.glassftp.ui.Fonts
import com.glassftp.ui.IosSwitch
import com.glassftp.ui.Navigator
import com.glassftp.ui.RoundDrawable
import com.glassftp.ui.Screen
import com.glassftp.ui.Segmented
import com.glassftp.ui.TextStyle
import com.glassftp.ui.Theme
import com.glassftp.ui.addCard
import com.glassftp.ui.confirm
import com.glassftp.ui.dp
import com.glassftp.ui.hbox
import com.glassftp.ui.icon
import com.glassftp.ui.linear
import com.glassftp.ui.onTap
import com.glassftp.ui.row
import com.glassftp.ui.sectionFooter
import com.glassftp.ui.sectionHeader
import com.glassftp.ui.text
import com.glassftp.ui.tile
import com.glassftp.ui.vbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsScreen(nav: Navigator) : Screen(nav) {
    override val title: CharSequence get() = str(R.string.tab_settings)

    private lateinit var concurrentLabel: TextView

    override fun createView(): View {
        val p = App.prefs
        val content = ctx.vbox()
        content.addView(largeTitleView())

        // Appearance
        content.addView(ctx.sectionHeader(str(R.string.appearance)))
        val look = Card(ctx)
        look.addRow(ctx.hbox {
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            addView(Segmented(ctx, listOf(str(R.string.theme_system), str(R.string.theme_light), str(R.string.theme_dark)), p.theme) { i ->
                p.theme = i
                view.postDelayed({ nav.activity.applyTheme() }, 280)
            }, linear(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        content.addCard(look)

        // Language
        content.addView(ctx.sectionHeader(str(R.string.language)))
        val lang = Card(ctx).apply { separatorInset = 60.dp }
        listOf("" to str(R.string.lang_system), "en" to "English", "fa" to "فارسی").forEach { (code, label) ->
            val check = ctx.icon(R.drawable.ic_check, Theme.accent, 20.dp).apply { visibility = if (p.language == code) View.VISIBLE else View.INVISIBLE }
            lang.addRow(ctx.row(label, leading = ctx.tile(R.drawable.ic_globe, Theme.indigo), trailing = check) {
                if (p.language != code) {
                    p.language = code
                    nav.activity.recreate()
                }
            })
        }
        content.addCard(lang)

        // Transfers
        content.addView(ctx.sectionHeader(str(R.string.transfers_section)))
        val tr = Card(ctx).apply { separatorInset = 60.dp }
        concurrentLabel = ctx.text(p.concurrentTransfers.toString(), TextStyle.Headline).apply { gravity = Gravity.CENTER; minWidth = 28.dp }
        val stepper = ctx.hbox {
            background = RoundDrawable(Theme.fill, 9f.dp)
            addView(stepButton("−") { setConcurrent(p.concurrentTransfers - 1) })
            addView(concurrentLabel)
            addView(stepButton("+") { setConcurrent(p.concurrentTransfers + 1) })
        }
        tr.addRow(ctx.row(str(R.string.simultaneous), leading = ctx.tile(R.drawable.ic_transfers, Theme.accent), trailing = stepper))
        tr.addRow(ctx.row(str(R.string.if_exists), leading = ctx.tile(R.drawable.ic_copy, Theme.orange),
            value = existsLabel(p.existsAction), chevron = true) { existsMenu() })
        tr.addRow(ctx.row(str(R.string.download_folder_setting), subtitle = p.downloadDir.absolutePath,
            leading = ctx.tile(R.drawable.ic_download, Theme.green), chevron = true) { chooseDownloadDir() })
        tr.addRow(ctx.row(str(R.string.keep_screen_on), leading = ctx.tile(R.drawable.ic_bolt, Theme.yellow),
            trailing = IosSwitch(ctx, p.keepScreenOn) { v -> p.keepScreenOn = v; nav.activity.applyKeepScreenOn() }))
        content.addCard(tr)
        content.addView(ctx.sectionFooter(str(R.string.transfers_footer)))

        // Browsing
        content.addView(ctx.sectionHeader(str(R.string.browsing)))
        val br = Card(ctx).apply { separatorInset = 60.dp }
        br.addRow(ctx.row(str(R.string.show_hidden_files), leading = ctx.tile(R.drawable.ic_eye, Theme.gray),
            trailing = IosSwitch(ctx, p.showHidden) { v -> p.showHidden = v }))
        content.addCard(br)

        // Data & security
        content.addView(ctx.sectionHeader(str(R.string.data_security)))
        val data = Card(ctx).apply { separatorInset = 60.dp }
        data.addRow(ctx.row(str(R.string.import_filezilla), subtitle = str(R.string.import_filezilla_sub),
            leading = ctx.tile(R.drawable.ic_import, Theme.red), chevron = true) { importFileZilla() })
        data.addRow(ctx.row(str(R.string.message_log), leading = ctx.tile(R.drawable.ic_log, Theme.teal), chevron = true) { nav.push(LogScreen(nav)) })
        data.addRow(ctx.row(str(R.string.forget_trusted), value = App.trust.count().toString(),
            leading = ctx.tile(R.drawable.ic_shield, Theme.purple)) { forgetTrusted() })
        content.addCard(data)
        content.addView(ctx.sectionFooter(str(R.string.security_footer)))

        // About
        content.addView(ctx.sectionHeader(str(R.string.about)))
        val about = Card(ctx)
        about.addRow(ctx.vbox {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(20.dp, 22.dp, 20.dp, 20.dp)
            addView(android.widget.ImageView(ctx).apply { setImageResource(R.mipmap.ic_launcher) }, linear(72.dp, 72.dp, gravity = Gravity.CENTER_HORIZONTAL))
            addView(ctx.text(str(R.string.app_name), TextStyle.Title2).apply { gravity = Gravity.CENTER },
                linear(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, gravity = Gravity.CENTER_HORIZONTAL, top = 10.dp))
            addView(ctx.text(str(R.string.version, BuildInfo.VERSION_NAME), TextStyle.Subhead, Theme.secondaryLabel).apply { gravity = Gravity.CENTER },
                linear(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, gravity = Gravity.CENTER_HORIZONTAL, top = 2.dp))
            addView(ctx.text(str(R.string.about_text), TextStyle.Footnote, Theme.secondaryLabel).apply {
                gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; setLineSpacing(2f.dp, 1f)
            }, linear(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, gravity = Gravity.CENTER_HORIZONTAL, top = 12.dp))
        })
        content.addCard(about)

        val scroll = ScrollView(ctx).apply { addView(content); isVerticalScrollBarEnabled = false }
        return scaffold(scroll)
    }

    private fun stepButton(label: String, onClick: () -> Unit) = ctx.text(label, TextStyle.Title3, Theme.label).apply {
        gravity = Gravity.CENTER
        typeface = Fonts.weight(500)
        minWidth = 40.dp
        minHeight = 32.dp
        onTap { onClick() }
    }

    private fun setConcurrent(n: Int) {
        App.prefs.concurrentTransfers = n
        concurrentLabel.text = App.prefs.concurrentTransfers.toString()
    }

    private fun existsLabel(a: ExistsAction) = str(when (a) {
        ExistsAction.OVERWRITE -> R.string.exists_overwrite
        ExistsAction.RESUME -> R.string.exists_resume
        ExistsAction.RENAME -> R.string.exists_rename
        ExistsAction.SKIP -> R.string.exists_skip
    })

    private fun existsMenu() {
        overlay.sheet(str(R.string.if_exists), null, ExistsAction.entries.map { a ->
            Action(existsLabel(a), icon = if (App.prefs.existsAction == a) R.drawable.ic_check else 0) {
                App.prefs.existsAction = a
                rebuild()
            }
        })
    }

    private fun chooseDownloadDir() {
        nav.activity.requestStorageAccess { ok ->
            if (!ok) { overlay.toast(str(R.string.storage_denied), R.drawable.ic_info, Theme.orange); return@requestStorageAccess }
            nav.push(LocalScreen(nav, LocalScreen.Mode.PICK_FOLDER, onPickFolder = { dir ->
                App.prefs.downloadDir = dir
                rebuild()
            }))
        }
    }

    private fun importFileZilla() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        nav.activity.launch(i) { code, data ->
            val uri = data?.data
            if (code != Activity.RESULT_OK || uri == null) return@launch
            scope.launch {
                val sites = withContext(Dispatchers.IO) {
                    runCatching { ctx.contentResolver.openInputStream(uri)?.use { FileZillaImport.parse(it) } }.getOrNull()
                }
                if (sites.isNullOrEmpty()) {
                    overlay.alert(str(R.string.import_none_title), str(R.string.import_none_msg), listOf(Action(str(R.string.ok), Action.Style.PREFERRED)))
                    return@launch
                }
                if (overlay.confirm(str(R.string.import_title), str(R.string.import_msg, sites.size), str(R.string.import_action), str(R.string.cancel))) {
                    sites.forEach { App.sites.save(it) }
                    overlay.toast(str(R.string.imported_n, sites.size), R.drawable.ic_check, Theme.green)
                }
            }
        }
    }

    private fun forgetTrusted() {
        scope.launch {
            if (overlay.confirm(str(R.string.forget_trusted), str(R.string.forget_trusted_msg), str(R.string.forget), str(R.string.cancel), destructive = true)) {
                App.trust.clear()
                rebuild()
            }
        }
    }

    private fun rebuild() {
        val y = (contentHost.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        nav.reload(this)
        if (isBuilt) (contentHost.getChildAt(0) as? ScrollView)?.let { sv -> sv.post { sv.scrollTo(0, y) } }
    }
}
