package com.glassftp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import com.glassftp.R
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class Action(
    val label: CharSequence,
    val style: Style = Style.DEFAULT,
    val icon: Int = 0,
    val handler: () -> Unit = {},
) {
    enum class Style { DEFAULT, PREFERRED, CANCEL, DESTRUCTIVE }
}

/** Hosts in-app glass alerts, action sheets and banners above everything else. */
@SuppressLint("ViewConstructor")
class OverlayHost(ctx: Context, private val source: BackdropHost) : FrameLayout(ctx) {
    private inner class Layer(val root: FrameLayout, val panel: View, val kind: Int, val onDismiss: () -> Unit)

    private val layers = mutableListOf<Layer>()
    var topInset = 0
    var bottomInset = 0
        set(v) { field = v; layers.forEach { relayout(it) } }

    init {
        clipChildren = false
    }

    val isShowing get() = layers.any { it.kind != KIND_TOAST }

    private fun relayout(l: Layer) {
        if (l.kind == KIND_ALERT) l.root.setPadding(0, topInset, 0, bottomInset)
        if (l.kind == KIND_SHEET) (l.panel.layoutParams as LayoutParams).bottomMargin = bottomInset + 10.dp
        l.panel.requestLayout()
    }

    private fun dimLayer(cancelable: Boolean, onOutside: () -> Unit) = FrameLayout(context).apply {
        setBackgroundColor(Theme.dim)
        alpha = 0f
        clipChildren = false
        isClickable = true
        if (cancelable) setOnClickListener { onOutside() }
    }

    fun dismissTop(): Boolean {
        val l = layers.lastOrNull { it.kind != KIND_TOAST } ?: return false
        dismiss(l)
        return true
    }

    private fun dismiss(l: Layer, then: (() -> Unit)? = null) {
        if (!layers.remove(l)) return
        hideKeyboard()
        l.root.animate().alpha(0f).setDuration(200).start()
        val anim = l.panel.animate().setDuration(220).setInterpolator(IosEase)
        when (l.kind) {
            KIND_SHEET -> anim.translationY(l.panel.height + bottomInset + 40f.dp)
            KIND_ALERT -> anim.scaleX(0.94f).scaleY(0.94f).alpha(0f)
            else -> anim.translationY(-(l.panel.height + topInset + 40f.dp)).alpha(0f)
        }
        anim.withEndAction { removeView(l.root); l.onDismiss(); then?.invoke() }.start()
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    /** Centered alert (UIAlertController .alert). Returns the field, if any. */
    fun alert(
        title: CharSequence,
        message: CharSequence? = null,
        actions: List<Action>,
        input: Input? = null,
        cancelable: Boolean = true,
        content: View? = null,
    ): EditText? {
        lateinit var layer: Layer
        val root = dimLayer(cancelable) { dismiss(layer) }
        root.setPadding(0, topInset, 0, bottomInset)
        val panel = GlassSurface(context, source, radius = 30f.dp, material = GlassSurface.Material.SHEET).apply {
            shadow = true
            isClickable = true
        }
        val box = context.vbox { setPadding(20.dp, 22.dp, 20.dp, 16.dp); gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(context.text(title, TextStyle.Headline).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
        if (!message.isNullOrEmpty()) {
            box.addView(context.text(message, TextStyle.Subhead, Theme.label).apply {
                gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; alpha = 0.85f
                setLineSpacing(2f.dp, 1f)
            }, linear(top = 6.dp))
        }
        var field: EditText? = null
        if (input != null) {
            field = context.field(input.hint, input.value, input.type).apply {
                background = RoundDrawable(Theme.fill, 12f.dp)
                setPadding(14.dp, 12.dp, 14.dp, 12.dp)
                imeOptions = EditorInfo.IME_ACTION_DONE
                if (input.selectName) {
                    val dot = input.value.lastIndexOf('.')
                    setSelection(0, if (dot > 0) dot else input.value.length)
                } else setSelection(input.value.length)
            }
            box.addView(field, linear(top = 16.dp))
        }
        if (content != null) box.addView(content, linear(top = 14.dp))

        val buttons = context.vbox()
        val horizontal = actions.size == 2
        val row = if (horizontal) context.hbox() else null
        actions.forEachIndexed { i, a ->
            val b = context.text(a.label, TextStyle.Headline, when (a.style) {
                Action.Style.DESTRUCTIVE -> Theme.red
                Action.Style.PREFERRED -> 0xFFFFFFFF.toInt()
                else -> Theme.label
            }, lines = 1).apply {
                typeface = Fonts.weight(if (a.style == Action.Style.CANCEL) 500 else 600)
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                minHeight = 48.dp
                background = RoundDrawable(if (a.style == Action.Style.PREFERRED) Theme.accent else Theme.fill, 0f,
                    capsule = true, pressedOverlay = true)
                onTap { dismiss(layer) { a.handler() } }
            }
            if (row != null) row.addView(b, linear(0, WRAP, 1f, start = if (i > 0) 10.dp else 0))
            else buttons.addView(b, linear(top = if (i > 0) 10.dp else 0))
        }
        if (row != null) buttons.addView(row, linear())
        box.addView(buttons, linear(top = 20.dp))

        field?.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) {
                actions.lastOrNull { it.style == Action.Style.PREFERRED }?.let { a -> dismiss(layer) { a.handler() } }
                true
            } else false
        }

        val scroll = ScrollView(context).apply { addView(box); isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER }
        panel.addView(scroll, frame())
        root.addView(panel, frame(minOf(320.dp, resources.displayMetrics.widthPixels - 40.dp), WRAP, Gravity.CENTER))
        layer = Layer(root, panel, KIND_ALERT) {}
        show(layer)
        panel.scaleX = 1.12f; panel.scaleY = 1.12f; panel.alpha = 0f
        panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300).setInterpolator(IosEase).start()
        field?.let { f ->
            f.requestFocus()
            f.postDelayed({ context.getSystemService(InputMethodManager::class.java).showSoftInput(f, 0) }, 150)
        }
        return field
    }

    /** Bottom action sheet (UIAlertController .actionSheet / context menu). */
    fun sheet(title: CharSequence? = null, message: CharSequence? = null, actions: List<Action>, cancelLabel: CharSequence? = null) {
        lateinit var layer: Layer
        val root = dimLayer(true) { dismiss(layer) }
        val panel = GlassSurface(context, source, radius = 32f.dp, material = GlassSurface.Material.SHEET).apply {
            shadow = true
            isClickable = true
        }
        val box = context.vbox { setPadding(0, 8.dp, 0, 10.dp) }
        if (title != null || message != null) {
            val head = context.vbox { setPadding(24.dp, 10.dp, 24.dp, 12.dp); gravity = Gravity.CENTER_HORIZONTAL }
            if (title != null) head.addView(context.text(title, TextStyle.Footnote, Theme.secondaryLabel, lines = 2).apply {
                typeface = Fonts.weight(600); textAlignment = View.TEXT_ALIGNMENT_CENTER })
            if (message != null) head.addView(context.text(message, TextStyle.Footnote, Theme.secondaryLabel, lines = 3).apply {
                textAlignment = View.TEXT_ALIGNMENT_CENTER }, linear(top = 2.dp))
            box.addView(head)
        }
        val list = context.vbox()
        val anyIcon = actions.any { it.icon != 0 }
        actions.forEach { a ->
            val color = if (a.style == Action.Style.DESTRUCTIVE) Theme.red else Theme.label
            val r = context.hbox {
                minimumHeight = 52.dp
                setPaddingRelative(24.dp, 0, 24.dp, 0)
                background = PressOverlay()
                if (a.icon != 0) addView(context.icon(a.icon, if (a.style == Action.Style.DESTRUCTIVE) Theme.red else Theme.accent, 22.dp),
                    linear(22.dp, 22.dp, end = 16.dp))
                else if (anyIcon) addView(android.view.View(context), linear(22.dp, 22.dp, end = 16.dp))
                addView(context.text(a.label, TextStyle.Body, color, lines = 1), linear(0, WRAP, 1f))
                onTap { dismiss(layer) { a.handler() } }
            }
            list.addView(r, linear())
        }
        val scroll = ScrollView(context).apply { addView(list); isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER }
        box.addView(scroll, linear(MATCH, WRAP))
        val maxH = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        scroll.layoutParams = linear(MATCH, if (actions.size * 52.dp > maxH) maxH else WRAP)
        val cancel = context.text(cancelLabel ?: context.getString(R.string.cancel), TextStyle.Headline, Theme.accent).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            minHeight = 50.dp
            background = RoundDrawable(Theme.fill, 0f, capsule = true, pressedOverlay = true)
            onTap { dismiss(layer) }
        }
        box.addView(cancel, linear(start = 14.dp, end = 14.dp, top = 6.dp))
        panel.addView(box, frame())
        root.addView(panel, frame(minOf(MATCH, MATCH), WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            start = 10.dp, end = 10.dp, bottom = bottomInset + 10.dp).apply {
            if (resources.displayMetrics.widthPixels > 600.dp) width = 480.dp
        })
        layer = Layer(root, panel, KIND_SHEET) {}
        show(layer)
        panel.translationY = 600f.dp
        panel.post {
            panel.translationY = panel.height + bottomInset + 40f.dp
            panel.animate().translationY(0f).setDuration(380).setInterpolator(IosEase).start()
        }
    }

    /** Banner at the top, like a notification. */
    fun toast(message: CharSequence, icon: Int = 0, iconColor: Int = Theme.accent) {
        layers.filter { it.kind == KIND_TOAST }.forEach { dismiss(it) }
        val root = FrameLayout(context).apply { clipChildren = false }
        val panel = GlassSurface(context, source, capsule = true, material = GlassSurface.Material.SHEET).apply { shadow = true }
        val row = context.hbox {
            setPaddingRelative(18.dp, 12.dp, 22.dp, 12.dp)
            if (icon != 0) addView(context.icon(icon, iconColor, 20.dp), linear(20.dp, 20.dp, end = 10.dp))
            addView(context.text(message, TextStyle.Subhead, Theme.label, lines = 2).apply { typeface = Fonts.weight(600) })
        }
        panel.addView(row, frame(WRAP, WRAP))
        root.addView(panel, frame(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL, top = topInset + 8.dp, start = 24.dp, end = 24.dp))
        val layer = Layer(root, panel, KIND_TOAST) {}
        layers += layer
        addView(root, frame(MATCH, WRAP))
        panel.translationY = -120f.dp
        panel.alpha = 0f
        panel.animate().translationY(0f).alpha(1f).setDuration(420).setInterpolator(IosEase).start()
        postDelayed({ dismiss(layer) }, 2600)
    }

    private fun show(l: Layer) {
        layers += l
        addView(l.root, frame(MATCH, MATCH))
        l.root.animate().alpha(1f).setDuration(220).start()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?) = false

    class Input(val hint: CharSequence = "", val value: String = "", val type: Int = InputType.TYPE_CLASS_TEXT,
                val selectName: Boolean = false)

    companion object {
        private const val KIND_ALERT = 0
        private const val KIND_SHEET = 1
        private const val KIND_TOAST = 2
    }
}

suspend fun OverlayHost.confirm(title: CharSequence, message: CharSequence?, ok: CharSequence, cancel: CharSequence,
                                destructive: Boolean = false): Boolean = suspendCancellableCoroutine { cont ->
    alert(title, message, listOf(
        Action(cancel, Action.Style.CANCEL) { if (cont.isActive) cont.resume(false) },
        Action(ok, if (destructive) Action.Style.DESTRUCTIVE else Action.Style.PREFERRED) { if (cont.isActive) cont.resume(true) },
    ), cancelable = false)
}

suspend fun OverlayHost.prompt(title: CharSequence, message: CharSequence?, input: OverlayHost.Input,
                               ok: CharSequence, cancel: CharSequence): String? = suspendCancellableCoroutine { cont ->
    var field: EditText? = null
    field = alert(title, message, listOf(
        Action(cancel, Action.Style.CANCEL) { if (cont.isActive) cont.resume(null) },
        Action(ok, Action.Style.PREFERRED) { if (cont.isActive) cont.resume(field?.text?.toString()) },
    ), input = input, cancelable = false)
}
