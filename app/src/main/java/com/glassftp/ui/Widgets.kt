package com.glassftp.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.glassftp.R

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Apple-like ease-out curve used for most motion. */
val IosEase = PathInterpolator(0.32f, 0.72f, 0f, 1f)

fun linear(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f, gravity: Int = -1,
           start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0) =
    LinearLayout.LayoutParams(w, h, weight).apply {
        if (gravity != -1) this.gravity = gravity
        marginStart = start; topMargin = top; marginEnd = end; bottomMargin = bottom
    }

fun frame(w: Int = MATCH, h: Int = WRAP, gravity: Int = Gravity.NO_GRAVITY,
          start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0) =
    FrameLayout.LayoutParams(w, h, gravity).apply {
        marginStart = start; topMargin = top; marginEnd = end; bottomMargin = bottom
    }

fun View.padding(h: Int = 0, v: Int = 0) = setPaddingRelative(h, v, h, v)

fun Context.vbox(block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; block()
}

fun Context.hbox(block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; block()
}

fun Context.text(s: CharSequence, st: TextStyle = TextStyle.Body, color: Int = Theme.label,
                 lines: Int = 0, block: TextView.() -> Unit = {}) = TextView(this).apply {
    text = s
    style(st, color)
    if (lines > 0) {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }
    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    block()
}

fun Context.icon(res: Int, color: Int = Theme.label, size: Int = 22.dp) = ImageView(this).apply {
    setImageResource(res)
    setColorFilter(color)
    scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = LinearLayout.LayoutParams(size, size)
}

/** iOS Settings-style colored rounded square with a white glyph. */
fun Context.tile(res: Int, color: Int, size: Int = 30.dp) = FrameLayout(this).apply {
    background = RoundDrawable(color, size * 0.24f)
    addView(ImageView(context).apply {
        setImageResource(res)
        setColorFilter(0xFFFFFFFF.toInt())
    }, frame((size * 0.62f).toInt(), (size * 0.62f).toInt(), Gravity.CENTER))
    layoutParams = LinearLayout.LayoutParams(size, size)
}

/** Dims to 40% while pressed, like UIButton. */
@SuppressLint("ClickableViewAccessibility")
fun View.onTap(block: (View) -> Unit) {
    isClickable = true
    setOnClickListener { block(it) }
    if (background == null || background !is RoundDrawable && background !is GroupedRowBackground && background !is PressOverlay) {
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().alpha(0.4f).setDuration(60).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().alpha(1f).setDuration(220).start()
            }
            false
        }
    }
}

fun Context.filledButton(label: CharSequence, color: Int = Theme.accent, onClick: (View) -> Unit) = TextView(this).apply {
    text = label
    style(TextStyle.Headline, 0xFFFFFFFF.toInt())
    gravity = Gravity.CENTER
    background = RoundDrawable(color, 0f, capsule = true, pressedOverlay = true)
    minHeight = 50.dp
    padding(20.dp, 14.dp)
    onTap(onClick)
}

fun Context.tintedButton(label: CharSequence, color: Int = Theme.accent, onClick: (View) -> Unit) = TextView(this).apply {
    text = label
    style(TextStyle.Headline, color)
    gravity = Gravity.CENTER
    background = RoundDrawable(ColorUtil.alpha(color, 0.15f), 0f, capsule = true, pressedOverlay = true)
    minHeight = 50.dp
    padding(20.dp, 14.dp)
    onTap(onClick)
}

fun Context.plainButton(label: CharSequence, color: Int = Theme.accent, st: TextStyle = TextStyle.Body,
                        weight: Int = st.weight, onClick: (View) -> Unit) = TextView(this).apply {
    text = label
    style(st, color, weight)
    gravity = Gravity.CENTER
    minHeight = 44.dp
    minWidth = 44.dp
    padding(8.dp, 0)
    onTap(onClick)
}

/** Circular floating glass button (toolbar items in iOS 26). */
fun Context.glassIconButton(res: Int, source: BackdropHost?, color: Int = Theme.label, size: Int = 44.dp,
                            onClick: (View) -> Unit) =
    GlassSurface(this, source, capsule = true, material = GlassSurface.Material.BUTTON).apply {
        addView(ImageView(context).apply { setImageResource(res); setColorFilter(color) },
            frame((size * 0.5f).toInt(), (size * 0.5f).toInt(), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(size, size)
        contentDescription = null
        onTap(onClick)
    }

/** Pressed highlight only (rows inside a card). */
class PressOverlay : android.graphics.drawable.Drawable() {
    private val paint = Paint()
    private var pressed = false
    override fun draw(canvas: Canvas) {
        if (!pressed) return
        paint.color = Theme.pressed
        canvas.drawRect(bounds, paint)
    }
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed)
        if (p != pressed) { pressed = p; invalidateSelf(); return true }
        return false
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** An inset-grouped section card. Use [addRow] so hairlines are inserted between rows. */
class Card(ctx: Context) : LinearLayout(ctx) {
    private var rows = 0
    var separatorInset = 16.dp

    init {
        orientation = VERTICAL
        background = RoundDrawable(Theme.cardFill, 22f.dp, strokeColor = ColorUtil.alpha(Theme.rimTop, 0.6f))
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, 22f.dp)
        }
    }

    fun addRow(v: View, inset: Int = separatorInset): View {
        if (rows > 0) addView(Hairline(context), linear(MATCH, maxOf(1, (0.5f.dp).toInt()), start = inset))
        addView(v, linear())
        rows++
        return v
    }
}

class Hairline(ctx: Context) : View(ctx) {
    init { setBackgroundColor(Theme.separator) }
}

/** A standard table row: [leading] title/subtitle … [trailing]. */
fun Context.row(
    title: CharSequence,
    subtitle: CharSequence? = null,
    leading: View? = null,
    trailing: View? = null,
    value: CharSequence? = null,
    chevron: Boolean = false,
    titleColor: Int = Theme.label,
    onClick: ((View) -> Unit)? = null,
): LinearLayout = hbox {
    minimumHeight = 52.dp
    setPaddingRelative(16.dp, 10.dp, 14.dp, 10.dp)
    if (leading != null) addView(leading, (leading.layoutParams as? LinearLayout.LayoutParams ?: linear(WRAP, WRAP)).apply { marginEnd = 14.dp })
    addView(vbox {
        addView(text(title, TextStyle.Body, titleColor, lines = 1))
        if (subtitle != null) addView(text(subtitle, TextStyle.Footnote, Theme.secondaryLabel, lines = 1), linear(top = 2.dp))
    }, linear(0, WRAP, 1f))
    if (value != null) addView(text(value, TextStyle.Body, Theme.secondaryLabel, lines = 1), linear(WRAP, WRAP, start = 8.dp))
    if (trailing != null) addView(trailing, (trailing.layoutParams as? LinearLayout.LayoutParams ?: linear(WRAP, WRAP)).apply { marginStart = 8.dp })
    if (chevron) addView(icon(R.drawable.ic_chevron_forward, Theme.tertiaryLabel, 14.dp).apply { autoMirror() }, linear(14.dp, 14.dp, start = 8.dp))
    if (onClick != null) {
        background = PressOverlay()
        onTap(onClick)
    }
}

fun ImageView.autoMirror() {
    if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) scaleX = -1f
}

fun Context.sectionHeader(s: CharSequence) = text(s, TextStyle.Footnote, Theme.secondaryLabel).apply {
    setPaddingRelative(36.dp, 22.dp, 36.dp, 7.dp)
}

fun Context.sectionFooter(s: CharSequence) = text(s, TextStyle.Footnote, Theme.secondaryLabel).apply {
    setPaddingRelative(36.dp, 7.dp, 36.dp, 4.dp)
}

/** Places a [Card] with iOS inset-grouped margins. */
fun LinearLayout.addCard(card: Card) = addView(card, linear(start = 16.dp, end = 16.dp))

fun Context.field(hint: CharSequence, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT,
                  onChange: (String) -> Unit = {}) = EditText(this).apply {
    setText(value)
    this.hint = hint
    style(TextStyle.Body)
    setHintTextColor(Theme.tertiaryLabel)
    background = null
    // setSingleLine() replaces the transformation method, so it must run before a password inputType.
    isSingleLine = type and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0
    inputType = type
    typeface = Fonts.weight(TextStyle.Body.weight) // password input types switch to monospace
    setPadding(0, 0, 0, 0)
    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    if (Build.VERSION.SDK_INT >= 29) {
        textCursorDrawable = GradientDrawable().apply { setColor(Theme.accent); setSize(2.dp, 22.dp) }
    }
    highlightColor = ColorUtil.alpha(Theme.accent, 0.3f)
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) = onChange(s?.toString() ?: "")
    })
}

/** "Label   [field]" row used in forms. */
fun Context.fieldRow(label: CharSequence, field: View, labelWidth: Int = 104.dp) = hbox {
    minimumHeight = 52.dp
    setPaddingRelative(16.dp, 0, 16.dp, 0)
    addView(text(label, TextStyle.Body, lines = 1), linear(labelWidth, WRAP))
    addView(field, linear(0, WRAP, 1f))
}

/** UISwitch. */
class IosSwitch(ctx: Context, initial: Boolean = false, var onChange: (Boolean) -> Unit = {}) : View(ctx) {
    var checked = initial
        private set
    private var pos = if (initial) 1f else 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var anim: ValueAnimator? = null

    init {
        isClickable = true
        setOnClickListener { set(!checked, true) }
        layoutParams = LinearLayout.LayoutParams(51.dp, 31.dp)
    }

    fun set(value: Boolean, notify: Boolean = false) {
        if (value == checked) return
        checked = value
        anim?.cancel()
        anim = ValueAnimator.ofFloat(pos, if (value) 1f else 0f).apply {
            duration = 260
            interpolator = IosEase
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
        if (notify) onChange(value)
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(51.dp, 31.dp)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val off = if (Theme.dark) 0xFF39393D.toInt() else 0xFFE9E9EA.toInt()
        paint.color = blend(off, Theme.green, pos)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        val r = h / 2 - 2f.dp
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val p = if (rtl) 1f - pos else pos
        val cx = h / 2 + (w - h) * p
        paint.color = Theme.knob
        if (Build.VERSION.SDK_INT >= 28) paint.setShadowLayer(3f.dp, 0f, 1.5f.dp, 0x33000000)
        canvas.drawCircle(cx, h / 2, r, paint)
        paint.clearShadowLayer()
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }
}

/** UISegmentedControl. */
class Segmented(ctx: Context, private val labels: List<String>, initial: Int = 0,
                var onChange: (Int) -> Unit = {}) : View(ctx) {
    var selected = initial
        private set
    private var thumb = initial.toFloat()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    init {
        isClickable = true
    }

    fun select(i: Int, notify: Boolean = false) {
        if (i == selected) return
        selected = i
        ValueAnimator.ofFloat(thumb, i.toFloat()).apply {
            duration = 280; interpolator = IosEase
            addUpdateListener { thumb = it.animatedValue as Float; invalidate() }
            start()
        }
        if (notify) onChange(i)
    }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(resolveSize(labels.size * 90.dp, w), 34.dp)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            var i = (e.x / (width.toFloat() / labels.size)).toInt().coerceIn(0, labels.size - 1)
            if (layoutDirection == LAYOUT_DIRECTION_RTL) i = labels.size - 1 - i
            select(i, true)
            performClick()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val seg = w / labels.size
        paint.color = Theme.fill
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, 9f.dp, 9f.dp, paint)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val pos = if (rtl) labels.size - 1 - thumb else thumb
        rect.set(pos * seg + 2.dp, 2f.dp, (pos + 1) * seg - 2.dp, h - 2.dp)
        paint.color = Theme.segmentSelected
        if (Build.VERSION.SDK_INT >= 28) paint.setShadowLayer(4f.dp, 0f, 2f.dp, 0x1F000000)
        canvas.drawRoundRect(rect, 7f.dp, 7f.dp, paint)
        paint.clearShadowLayer()
        textPaint.textSize = 13f * resources.displayMetrics.scaledDensity
        textPaint.color = Theme.label
        labels.forEachIndexed { i, s ->
            val idx = if (rtl) labels.size - 1 - i else i
            textPaint.typeface = Fonts.weight(if (i == selected) 600 else 500)
            val cx = idx * seg + seg / 2
            val cy = h / 2 - (textPaint.descent() + textPaint.ascent()) / 2
            val label = TextUtils.ellipsize(s, android.text.TextPaint(textPaint), seg - 8.dp, TextUtils.TruncateAt.END).toString()
            canvas.drawText(label, cx, cy, textPaint)
        }
    }
}

/** UIActivityIndicatorView (medium). */
class ActivityIndicator(ctx: Context, private val size: Int = 20.dp, var color: Int = Theme.secondaryLabel) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private var step = 0
    private val tick = object : Runnable {
        override fun run() { step = (step + 1) % 8; invalidate(); postDelayed(this, 110) }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(size, size)
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val c = size / 2f
        paint.strokeWidth = size * 0.11f
        for (i in 0 until 8) {
            val age = (step - i + 8) % 8
            paint.color = ColorUtil.alpha(color, 1f - age / 9f)
            canvas.save()
            canvas.rotate(i * 45f, c, c)
            canvas.drawLine(c, size * 0.08f, c, size * 0.3f, paint)
            canvas.restore()
        }
    }
}

/** Thin UIProgressView. */
class ProgressLine(ctx: Context) : View(ctx) {
    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var color = Theme.accent
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), 4.dp)

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        rect.set(0f, 0f, width.toFloat(), h)
        paint.color = Theme.fill
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val pw = width * progress
        if (rtl) rect.set(width - pw, 0f, width.toFloat(), h) else rect.set(0f, 0f, pw, h)
        paint.color = color
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
    }
}

/** Selection checkmark circle used in edit mode. */
class CheckCircle(ctx: Context) : View(ctx) {
    var checked = false
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(24.dp, 24.dp)

    override fun onDraw(canvas: Canvas) {
        val c = width / 2f
        if (checked) {
            paint.style = Paint.Style.FILL
            paint.color = Theme.accent
            canvas.drawCircle(c, c, c - 1.dp, paint)
            paint.color = 0xFFFFFFFF.toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f.dp
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            val p = android.graphics.Path().apply {
                moveTo(c * 0.55f, c * 1.02f); lineTo(c * 0.88f, c * 1.33f); lineTo(c * 1.45f, c * 0.7f)
            }
            canvas.drawPath(p, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f.dp
            paint.color = Theme.tertiaryLabel
            canvas.drawCircle(c, c, c - 1.5f.dp, paint)
        }
    }
}
