package com.glassftp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.glassftp.MainActivity
import com.glassftp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** A screen in a navigation stack (UIViewController-ish). Views can be rebuilt (theme change). */
abstract class Screen(val nav: Navigator) {
    val ctx: Context get() = nav.activity
    val overlay get() = nav.overlay
    val scope = CoroutineScope(SupervisorJob() + Main)

    private var built: View? = null
    val view: View get() = built ?: createView().also { built = it; applyInsets() }
    val isBuilt get() = built != null

    open val showsTabBar = true
    open val title: CharSequence get() = ""

    lateinit var navBar: NavBar
    lateinit var contentHost: BackdropHost
    private var scrollTarget: View? = null
    private var largeTitle: View? = null
    private var hasLargeTitle = false

    abstract fun createView(): View
    open fun onAppear() {}
    open fun onDisappear() {}
    /** Return true if the screen consumed the back press (e.g. left selection mode). */
    open fun onBack(): Boolean = false
    open fun onDestroy() = scope.cancel()

    fun invalidateView() { built = null }

    fun str(id: Int, vararg args: Any): String = ctx.getString(id, *args)

    /** Content padding that keeps it clear of the nav bar and floating tab bar. */
    val contentTop get() = nav.topInset + NavBar.HEIGHT
    val contentBottom get() = maxOf(nav.imeInset, nav.navInset + if (showsTabBar) TabBar.HEIGHT + 16.dp else 0) + 24.dp

    open fun applyInsets() {
        if (!isBuilt) return
        if (this::navBar.isInitialized) navBar.setTopInset(nav.topInset)
        when (val s = scrollTarget) {
            is ListView -> s.setPadding(s.paddingLeft, contentTop, s.paddingRight, contentBottom)
            is ScrollView -> (s.getChildAt(0))?.setPadding(0, contentTop, 0, contentBottom)
        }
    }

    /** Standard layout: blurred nav bar over a scrolling content view with an optional large title. */
    protected fun scaffold(content: View, largeTitle: Boolean = true): FrameLayout {
        val root = FrameLayout(ctx).apply { clipChildren = false }
        contentHost = BackdropHost(ctx)
        contentHost.addView(content, frame(MATCH, MATCH))
        root.addView(contentHost, frame(MATCH, MATCH))
        navBar = NavBar(ctx, contentHost)
        navBar.setTitle(title)
        hasLargeTitle = largeTitle
        navBar.titleAlpha(if (largeTitle) 0f else 1f)
        navBar.material(0f)
        if (nav.canGoBack(this)) navBar.setBack { nav.pop() }
        root.addView(navBar, frame(MATCH, WRAP, Gravity.TOP))
        scrollTarget = content
        when (content) {
            is ListView -> {
                content.clipToPadding = false
                content.setOnScrollListener(object : AbsListView.OnScrollListener {
                    override fun onScrollStateChanged(v: AbsListView?, state: Int) {}
                    override fun onScroll(v: AbsListView, first: Int, visible: Int, total: Int) = onScrolled(listOffset(v))
                })
            }
            is ScrollView -> {
                content.clipToPadding = false
                content.setOnScrollChangeListener { _, _, y, _, _ -> onScrolled(y) }
            }
        }
        return root
    }

    private fun listOffset(v: AbsListView): Int {
        if (v.childCount == 0) return 0
        if (v.firstVisiblePosition > 0) return 10_000
        return -(v.getChildAt(0).top - v.paddingTop)
    }

    fun largeTitleView(text: CharSequence = title): TextView = ctx.text(text, TextStyle.LargeTitle, lines = 1).apply {
        setPaddingRelative(20.dp, 4.dp, 20.dp, 10.dp)
        largeTitle = this
    }

    private fun onScrolled(y: Int) {
        if (!this::navBar.isInitialized) return
        navBar.material(y / 10f.dp)
        if (hasLargeTitle) {
            val h = (largeTitle?.height ?: 40.dp).toFloat()
            navBar.titleAlpha((y - h * 0.55f) / (h * 0.35f))
        }
    }
}

/** Top bar: floating glass buttons + title; material fades in when content scrolls under it. */
@SuppressLint("ViewConstructor")
class NavBar(ctx: Context, source: BackdropHost) : GlassSurface(ctx, source, material = Material.BAR) {
    private val band = FrameLayout(ctx)
    private val titleView = ctx.text("", TextStyle.Headline, lines = 1).apply {
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
    }
    private val leading = ctx.hbox()
    private val trailing = ctx.hbox()
    private val src = source

    init {
        rim = false
        clipChildren = false
        band.clipChildren = false
        band.addView(titleView, frame(MATCH, WRAP, Gravity.CENTER, start = 110.dp, end = 110.dp))
        band.addView(leading, frame(WRAP, MATCH, Gravity.START or Gravity.CENTER_VERTICAL, start = 12.dp))
        band.addView(trailing, frame(WRAP, MATCH, Gravity.END or Gravity.CENTER_VERTICAL, end = 12.dp))
        addView(band, frame(MATCH, HEIGHT, Gravity.BOTTOM))
    }

    fun setTopInset(top: Int) {
        layoutParams = (layoutParams ?: frame()).apply { height = top + HEIGHT }
        requestLayout()
    }

    fun setTitle(t: CharSequence) { titleView.text = t }
    fun titleAlpha(a: Float) { titleView.alpha = a.coerceIn(0f, 1f) }
    fun material(a: Float) {
        materialAlpha = a
        hairlineAlpha = a
    }

    fun setBack(onBack: () -> Unit) {
        leading.removeAllViews()
        leading.addView(context.glassIconButton(R.drawable.ic_chevron_back, src, Theme.label) { onBack() }.apply {
            (getChildAt(0) as ImageView).autoMirror()
        })
    }

    fun setLeading(vararg views: View) {
        leading.removeAllViews()
        views.forEach { leading.addView(it, it.layoutParams ?: linear(WRAP, WRAP)) }
    }

    fun setTrailing(vararg views: View) {
        trailing.removeAllViews()
        views.forEachIndexed { i, v ->
            val lp = (v.layoutParams as? LinearLayout.LayoutParams) ?: linear(WRAP, WRAP)
            lp.marginStart = if (i > 0) 8.dp else 0
            trailing.addView(v, lp)
        }
    }

    /** Several icon actions in one glass capsule, like iOS 26 toolbar groups. */
    fun group(vararg items: Pair<Int, (View) -> Unit>): View {
        val g = GlassSurface(context, src, capsule = true, material = Material.BUTTON)
        val row = context.hbox { setPadding(4.dp, 0, 4.dp, 0) }
        items.forEach { (res, cb) ->
            row.addView(FrameLayout(context).apply {
                addView(ImageView(context).apply { setImageResource(res); setColorFilter(Theme.label) }, frame(22.dp, 22.dp, Gravity.CENTER))
                onTap(cb)
            }, linear(42.dp, 44.dp))
        }
        g.addView(row, frame(WRAP, 44.dp))
        g.layoutParams = linear(WRAP, 44.dp)
        return g
    }

    /** Text action in a glass capsule ("Save", "Done"…). */
    fun textButton(label: CharSequence, prominent: Boolean = false, onClick: (View) -> Unit): View {
        val g = GlassSurface(context, src, capsule = true, material = Material.BUTTON)
        if (prominent) g.tint = Theme.accent
        g.addView(context.text(label, TextStyle.Headline, if (prominent) 0xFFFFFFFF.toInt() else Theme.label, lines = 1).apply {
            gravity = Gravity.CENTER
            setPadding(16.dp, 0, 16.dp, 0)
        }, frame(WRAP, 44.dp))
        g.layoutParams = linear(WRAP, 44.dp)
        g.onTap(onClick)
        return g
    }

    companion object {
        val HEIGHT get() = 56.dp
    }
}

/** Floating capsule tab bar with a sliding selection pill. */
@SuppressLint("ViewConstructor")
class TabBar(ctx: Context, source: BackdropHost, private val items: List<Pair<Int, String>>,
             private val onSelect: (Int) -> Unit) : FrameLayout(ctx) {
    private val capsule = GlassSurface(ctx, source, capsule = true, material = GlassSurface.Material.BAR).apply { shadow = true }
    private val pill = View(ctx).apply { background = RoundDrawable(Theme.fill, 0f, capsule = true) }
    private val row = ctx.hbox()
    private val icons = mutableListOf<ImageView>()
    private val labels = mutableListOf<TextView>()
    private val badges = mutableListOf<TextView>()
    var selected = 0
        private set

    init {
        clipChildren = false
        capsule.clipChildren = false
        capsule.addView(pill, frame(0, MATCH))
        capsule.addView(row, frame(MATCH, MATCH))
        items.forEachIndexed { i, (res, label) ->
            val cell = FrameLayout(ctx)
            val col = ctx.vbox { gravity = Gravity.CENTER }
            val ic = ctx.icon(res, Theme.label, 25.dp)
            val tv = ctx.text(label, TextStyle.Caption2, Theme.label, lines = 1).apply { gravity = Gravity.CENTER }
            col.addView(ic, linear(25.dp, 25.dp, gravity = Gravity.CENTER_HORIZONTAL))
            col.addView(tv, linear(WRAP, WRAP, gravity = Gravity.CENTER_HORIZONTAL, top = 3.dp))
            cell.addView(col, frame(MATCH, MATCH, Gravity.CENTER))
            val badge = ctx.text("", TextStyle.Caption2, 0xFFFFFFFF.toInt()).apply {
                background = RoundDrawable(Theme.red, 0f, capsule = true)
                gravity = Gravity.CENTER
                minWidth = 18.dp
                setPadding(5.dp, 2.dp, 5.dp, 2.dp)
                visibility = View.GONE
            }
            cell.addView(badge, frame(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL, start = 26.dp, top = 6.dp))
            cell.onTap { select(i, true) }
            icons += ic; labels += tv; badges += badge
            row.addView(cell, linear(0, MATCH, 1f))
        }
        addView(capsule, frame(MATCH, HEIGHT, Gravity.BOTTOM))
        colorize()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        placePill(false)
    }

    private fun cellWidth() = capsule.width / items.size.toFloat()

    private fun placePill(animate: Boolean) {
        val cw = cellWidth()
        if (cw <= 0) return
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val idx = if (rtl) items.size - 1 - selected else selected
        val lp = pill.layoutParams as LayoutParams
        val w = (cw - 8.dp).toInt()
        if (lp.width != w) {
            lp.width = w
            lp.topMargin = 4.dp; lp.bottomMargin = 4.dp
            pill.layoutParams = lp
        }
        val x = idx * cw + 4.dp
        if (animate) pill.animate().translationX(x).setDuration(320).setInterpolator(IosEase).start()
        else pill.translationX = x
    }

    fun select(i: Int, notify: Boolean = false) {
        if (i != selected) {
            selected = i
            placePill(true)
            colorize()
        }
        if (notify) onSelect(i)
    }

    fun setBadge(i: Int, count: Int) {
        val b = badges[i]
        b.visibility = if (count > 0) View.VISIBLE else View.GONE
        b.text = if (count > 99) "99+" else count.toString()
    }

    private fun colorize() {
        icons.forEachIndexed { i, v -> v.setColorFilter(if (i == selected) Theme.accent else Theme.label) }
        labels.forEachIndexed { i, v ->
            v.setTextColor(if (i == selected) Theme.accent else Theme.label)
            v.typeface = Fonts.weight(if (i == selected) 600 else 500)
        }
    }

    companion object {
        val HEIGHT get() = 64.dp
    }
}

/** Owns the tab stacks and drives transitions. */
class Navigator(
    val activity: MainActivity,
    private var container: FrameLayout,
    overlay: OverlayHost,
) {
    lateinit var tabBar: TabBar
    var overlay = overlay
        private set
    private val stacks = Array(4) { mutableListOf<Screen>() }
    var tab = 0
        private set
    var topInset = 0; private set
    var navInset = 0; private set
    var imeInset = 0; private set
    private var animating = false

    val current: Screen? get() = stacks[tab].lastOrNull()

    fun canGoBack(s: Screen) = stacks.any { st -> st.indexOf(s) > 0 }

    fun stack(i: Int): List<Screen> = stacks[i]

    fun setRoot(i: Int, s: Screen) {
        stacks[i].forEach { it.onDestroy() }
        stacks[i].clear()
        stacks[i] += s
    }

    fun start(initialTab: Int) {
        tab = initialTab
        tabBar.select(tab)
        current?.let { attach(it); it.onAppear(); updateTabBar(it, false) }
    }

    private fun attach(s: Screen) {
        val v = s.view
        (v.parent as? ViewGroup)?.removeView(v)
        v.translationX = 0f
        v.alpha = 1f
        container.addView(v, frame(MATCH, MATCH))
    }

    private fun dir() = if (container.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f

    fun push(s: Screen) {
        if (animating) return
        val from = current
        stacks[tab] += s
        attach(s)
        s.onAppear()
        from?.onDisappear()
        updateTabBar(s, true)
        val w = container.width.toFloat().coerceAtLeast(1f)
        animating = true
        s.view.translationX = w * 0.35f * dir()
        s.view.alpha = 0f
        s.view.animate().translationX(0f).alpha(1f).setDuration(380).setInterpolator(IosEase)
            .withEndAction { animating = false }.start()
        from?.view?.animate()?.translationX(-w * 0.18f * dir())?.alpha(0f)?.setDuration(300)?.setInterpolator(IosEase)
            ?.withEndAction { container.removeView(from.view); from.view.translationX = 0f; from.view.alpha = 1f }?.start()
    }

    fun pop(): Boolean {
        val st = stacks[tab]
        if (st.size <= 1 || animating) return false
        val top = st.removeAt(st.size - 1)
        val below = st.last()
        attach(below)
        below.view.translationX = -container.width * 0.18f * dir()
        below.view.alpha = 0f
        container.bringChildToFront(top.view)
        top.onDisappear()
        below.onAppear()
        updateTabBar(below, true)
        animating = true
        below.view.animate().translationX(0f).alpha(1f).setDuration(360).setInterpolator(IosEase).start()
        top.view.animate().translationX(container.width * 0.35f * dir()).alpha(0f).setDuration(300).setInterpolator(IosEase)
            .withEndAction {
                container.removeView(top.view)
                top.onDestroy()
                animating = false
            }.start()
        return true
    }

    fun popToRoot(i: Int = tab) {
        val st = stacks[i]
        if (st.size <= 1) return
        if (i == tab) {
            while (st.size > 2) st.removeAt(1).onDestroy()
            pop()
        } else {
            while (st.size > 1) st.removeAt(1).onDestroy()
        }
    }

    fun remove(s: Screen) {
        stacks.forEach { st ->
            val i = st.indexOf(s)
            if (i > 0) {
                if (st === stacks[tab] && i == st.size - 1) pop() else { st.removeAt(i); s.onDestroy() }
            }
        }
    }

    fun switchTab(i: Int) {
        if (i == tab) { popToRoot(); return }
        val from = current
        tab = i
        val to = current ?: return
        tabBar.select(i)
        from?.onDisappear()
        attach(to)
        to.onAppear()
        updateTabBar(to, true)
        to.view.alpha = 0f
        to.view.animate().alpha(1f).setDuration(200).start()
        from?.view?.let { v -> container.removeView(v) }
    }

    fun back(): Boolean {
        if (overlay.dismissTop()) return true
        val c = current ?: return false
        if (c.onBack()) return true
        if (pop()) return true
        if (tab != 0) { switchTab(0); return true }
        return false
    }

    /** Re-evaluates whether the current screen wants the tab bar. */
    fun refreshChrome() { current?.let { updateTabBar(it, true) } }

    fun rebind(container: FrameLayout, overlay: OverlayHost, tabBar: TabBar) {
        this.container = container
        this.overlay = overlay
        this.tabBar = tabBar
        applyInsets(topInset, navInset, imeInset)
    }

    private fun updateTabBar(s: Screen, animate: Boolean) {
        val target = if (s.showsTabBar) 0f else (TabBar.HEIGHT + navInset + 40.dp).toFloat()
        if (animate) tabBar.animate().translationY(target).setDuration(320).setInterpolator(IosEase).start()
        else tabBar.translationY = target
    }

    fun applyInsets(top: Int, nav: Int, ime: Int) {
        topInset = top; navInset = nav; imeInset = ime
        overlay.topInset = top
        overlay.bottomInset = maxOf(nav, ime)
        (tabBar.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.bottomMargin = nav + 10.dp
            tabBar.layoutParams = it
        }
        stacks.forEach { st -> st.forEach { it.applyInsets() } }
    }

    /** Rebuilds one screen's views; swaps them in place if it is on screen. */
    fun reload(s: Screen) {
        val old = if (s.isBuilt) s.view else null
        val attached = old != null && old.parent === container
        s.invalidateView()
        if (attached) {
            container.removeView(old)
            attach(s)
        }
    }

    /** Re-creates every view (after a theme change) while keeping screen state. */
    fun rebuild() {
        container.removeAllViews()
        stacks.forEach { st -> st.forEach { it.invalidateView() } }
        current?.let { attach(it); it.onAppear(); updateTabBar(it, false) }
    }

    fun all(): List<Screen> = stacks.flatMap { it }
}
