package com.glassftp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.glassftp.transfer.Direction
import com.glassftp.transfer.Transfer
import com.glassftp.transfer.TransferState
import com.glassftp.ui.BackdropHost
import com.glassftp.ui.Navigator
import com.glassftp.ui.OverlayHost
import com.glassftp.ui.TabBar
import com.glassftp.ui.Theme
import com.glassftp.ui.WallpaperView
import com.glassftp.ui.dp
import com.glassftp.ui.frame
import com.glassftp.ui.screens.LocalScreen
import com.glassftp.ui.screens.SettingsScreen
import com.glassftp.ui.screens.SitesScreen
import com.glassftp.ui.screens.TransfersScreen

class MainActivity : Activity() {
    lateinit var nav: Navigator
        private set
    private lateinit var root: FrameLayout
    private val results = HashMap<Int, (Int, Intent?) -> Unit>()
    private val permissionResults = HashMap<Int, (Boolean) -> Unit>()
    private var nextRequest = 100
    private var builtDark = false

    private val transfersListener = { updateBadge() }
    private val finishListener = { t: Transfer -> onTransferFinished(t) }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(App.localized(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.update(this)
        builtDark = Theme.dark
        setupWindow()
        root = FrameLayout(this)
        root.clipChildren = false
        setContentView(root)

        val parts = buildChrome()
        nav = Navigator(this, parts.first, parts.second)
        nav.tabBar = parts.third
        nav.setRoot(0, SitesScreen(nav))
        nav.setRoot(1, LocalScreen(nav))
        nav.setRoot(2, TransfersScreen(nav))
        nav.setRoot(3, SettingsScreen(nav))
        nav.start(intent?.getIntExtra(EXTRA_TAB, 0) ?: 0)

        root.setOnApplyWindowInsetsListener { _, insets -> onInsets(insets); insets }
        App.transfers.addListener(transfersListener)
        App.transfers.addFinishListener(finishListener)
        applyKeepScreenOn()
    }

    override fun onDestroy() {
        App.transfers.removeListener(transfersListener)
        App.transfers.removeFinishListener(finishListener)
        nav.all().forEach { it.onDestroy() }
        super.onDestroy()
    }

    /** wallpaper + screens container inside the backdrop host; tab bar and overlays above. */
    private fun buildChrome(): Triple<FrameLayout, OverlayHost, TabBar> {
        root.removeAllViews()
        val host = BackdropHost(this)
        host.addView(WallpaperView(this), frame(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val container = FrameLayout(this)
        host.addView(container, frame(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(host, frame(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val tabBar = TabBar(this, host, listOf(
            R.drawable.ic_server to getString(R.string.tab_servers),
            R.drawable.ic_folder to getString(R.string.tab_files),
            R.drawable.ic_transfers to getString(R.string.tab_transfers),
            R.drawable.ic_gear to getString(R.string.tab_settings),
        )) { i -> nav.switchTab(i) }
        val w = minOf(resources.displayMetrics.widthPixels - 32.dp, 440.dp)
        root.addView(tabBar, frame(w, TabBar.HEIGHT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        val overlay = OverlayHost(this, host)
        root.addView(overlay, frame(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return Triple(container, overlay, tabBar)
    }

    private fun setupWindow() {
        val w = window
        w.decorView // installs the decor; insetsController is null-unsafe before that
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 28) {
            w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        @Suppress("DEPRECATION")
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
            val light = if (Theme.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            w.insetsController?.setSystemBarsAppearance(light,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        } else {
            @Suppress("DEPRECATION")
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            @Suppress("DEPRECATION")
            if (!Theme.dark) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = flags
        }
        w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (Theme.dark) 0xFF07080F.toInt() else 0xFFEEF2FC.toInt()))
    }

    private fun onInsets(insets: WindowInsets) {
        val top: Int
        val bottom: Int
        val ime: Int
        val left: Int
        val right: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            top = bars.top; bottom = bars.bottom; left = bars.left; right = bars.right
            ime = insets.getInsets(WindowInsets.Type.ime()).bottom
        } else {
            @Suppress("DEPRECATION")
            top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val sysBottom = insets.systemWindowInsetBottom
            @Suppress("DEPRECATION")
            bottom = insets.stableInsetBottom
            @Suppress("DEPRECATION")
            left = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            right = insets.systemWindowInsetRight
            ime = if (sysBottom > bottom + 40.dp) sysBottom else 0
        }
        root.setPadding(left, 0, right, 0)
        nav.applyInsets(top, bottom, ime)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Theme.update(this)
        if (Theme.dark != builtDark) applyTheme() else root.requestApplyInsets()
    }

    /** Rebuilds all chrome and screen views in the current theme, keeping sessions alive. */
    fun applyTheme() {
        Theme.update(this)
        builtDark = Theme.dark
        setupWindow()
        val tab = nav.tab
        val parts = buildChrome()
        nav.rebind(parts.first, parts.second, parts.third)
        nav.tabBar.select(tab)
        nav.rebuild()
        root.requestApplyInsets()
        updateBadge()
    }

    fun applyKeepScreenOn() {
        if (App.prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun updateBadge() {
        val active = App.transfers.snapshot().count { !it.isFolder && !it.state.finished }
        nav.tabBar.setBadge(2, active)
    }

    private fun onTransferFinished(t: Transfer) {
        if (t.silent || t.isFolder) return
        when (t.state) {
            TransferState.DONE -> nav.overlay.toast(
                getString(if (t.direction == Direction.DOWNLOAD) R.string.toast_downloaded else R.string.toast_uploaded, t.name),
                if (t.direction == Direction.DOWNLOAD) R.drawable.ic_download else R.drawable.ic_upload, Theme.green)
            TransferState.FAILED -> nav.overlay.toast(getString(R.string.toast_failed, t.name), R.drawable.ic_close, Theme.red)
            else -> {}
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_TAB)) nav.switchTab(intent.getIntExtra(EXTRA_TAB, 0))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!nav.back()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------- results & permissions

    fun launch(intent: Intent, onResult: (Int, Intent?) -> Unit) {
        val code = nextRequest++
        results[code] = onResult
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, code)
        } catch (e: Exception) {
            results.remove(code)
            nav.overlay.toast(getString(R.string.no_app_found), R.drawable.ic_info, Theme.orange)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        results.remove(requestCode)?.invoke(resultCode, data)
    }

    fun requestPerms(perms: Array<String>, onResult: (Boolean) -> Unit) {
        if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) { onResult(true); return }
        val code = nextRequest++
        permissionResults[code] = onResult
        requestPermissions(perms, code)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionResults.remove(requestCode)?.invoke(grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
    }

    fun hasStorageAccess(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestStorageAccess(onResult: (Boolean) -> Unit) {
        if (hasStorageAccess()) { onResult(true); return }
        if (Build.VERSION.SDK_INT >= 30) {
            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            launch(i) { _, _ -> onResult(hasStorageAccess()) }
        } else {
            requestPerms(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), onResult)
        }
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) requestPerms(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) {}
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}
