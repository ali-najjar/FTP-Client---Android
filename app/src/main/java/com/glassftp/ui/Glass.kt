package com.glassftp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout

/** Soft, colorful iOS-style wallpaper. Rendered once at low resolution. */
class WallpaperView(ctx: Context) : View(ctx) {
    private var bitmap: Bitmap? = null
    private var builtDark: Boolean? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()

    private fun build(w: Int, h: Int) {
        val scale = 0.25f
        val bw = (w * scale).toInt().coerceAtLeast(1)
        val bh = (h * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val dark = Theme.dark
        val base = Paint().apply {
            shader = LinearGradient(0f, 0f, bw * 0.3f, bh.toFloat(),
                if (dark) intArrayOf(0xFF07080F.toInt(), 0xFF0E0A18.toInt(), 0xFF05060A.toInt())
                else intArrayOf(0xFFEAF0FF.toInt(), 0xFFF5F1FA.toInt(), 0xFFFDEFF4.toInt()),
                null, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), base)
        val m = maxOf(bw, bh).toFloat()
        val blobs = if (dark) listOf(
            Blob(0.10f, 0.10f, 0.60f, 0xFF0A84FF.toInt(), 0.50f),
            Blob(0.95f, 0.28f, 0.55f, 0xFFBF5AF2.toInt(), 0.38f),
            Blob(0.15f, 0.70f, 0.55f, 0xFFFF375F.toInt(), 0.22f),
            Blob(0.90f, 0.92f, 0.60f, 0xFF30D158.toInt(), 0.16f),
            Blob(0.50f, 0.45f, 0.40f, 0xFF5E5CE6.toInt(), 0.26f),
        ) else listOf(
            Blob(0.10f, 0.08f, 0.60f, 0xFF5AC8FA.toInt(), 0.55f),
            Blob(0.95f, 0.25f, 0.55f, 0xFFAF52DE.toInt(), 0.32f),
            Blob(0.12f, 0.72f, 0.60f, 0xFFFF2D55.toInt(), 0.20f),
            Blob(0.88f, 0.92f, 0.60f, 0xFF34C759.toInt(), 0.24f),
            Blob(0.55f, 0.50f, 0.45f, 0xFFFF9500.toInt(), 0.10f),
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (b in blobs) {
            val cx = b.x * bw
            val cy = b.y * bh
            val r = b.r * m
            val c0 = ColorUtil.alpha(b.color, b.a)
            val c1 = ColorUtil.alpha(b.color, b.a * 0.45f)
            p.shader = RadialGradient(cx, cy, r, intArrayOf(c0, c1, Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)
        }
        bitmap = bmp
        builtDark = dark
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bitmap = null
    }

    fun refresh() {
        bitmap = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        if (bitmap == null || builtDark != Theme.dark) build(width, height)
        dst.set(0, 0, width, height)
        canvas.drawBitmap(bitmap!!, null, dst, paint)
    }

    private class Blob(val x: Float, val y: Float, val r: Float, val color: Int, val a: Float)
}

/**
 * Records its children into a [RenderNode] so [GlassSurface]s elsewhere in the
 * tree can draw a blurred copy of it (real backdrop blur on Android 12+).
 */
open class BackdropHost(ctx: Context) : FrameLayout(ctx) {
    val node: RenderNode? = if (Build.VERSION.SDK_INT >= 31) RenderNode("backdrop") else null

    override fun dispatchDraw(canvas: Canvas) {
        val n = node
        if (n == null || !canvas.isHardwareAccelerated || Build.VERSION.SDK_INT < 31) {
            super.dispatchDraw(canvas)
            return
        }
        n.setPosition(0, 0, width, height)
        val rc = n.beginRecording(width, height)
        try {
            super.dispatchDraw(rc)
        } finally {
            n.endRecording()
        }
        canvas.drawRenderNode(n)
    }
}

/**
 * Frosted "liquid glass" material: blurred, saturated backdrop + tint + specular rim.
 * Falls back to a near-opaque tint where RenderEffect is unavailable.
 */
@SuppressLint("ViewConstructor")
open class GlassSurface(
    ctx: Context,
    var source: BackdropHost? = null,
    var radius: Float = 0f,
    var capsule: Boolean = false,
    var material: Material = Material.BAR,
) : FrameLayout(ctx) {
    enum class Material { BAR, SHEET, CARD, BUTTON }

    var rim = true
    var shadow = false
    var hairlineAlpha = 0f
        set(v) { if (field != v) { field = v; invalidate() } }
    var roundTop = true
    var roundBottom = true
    var tint: Int? = null
        set(v) { field = v; invalidate() }
    /** 0 hides the material entirely (e.g. a nav bar over the top of its content). */
    var materialAlpha = 1f
        set(v) { val c = v.coerceIn(0f, 1f); if (field != c) { field = c; invalidate() } }

    private val blurNode: RenderNode? = if (Build.VERSION.SDK_INT >= 31) RenderNode("glass") else null
    private var effectRadius = -1f
    private val path = Path()
    private val rect = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hairPaint = Paint()
    private val myLoc = IntArray(2)
    private val srcLoc = IntArray(2)
    private val preDraw = ViewTreeObserver.OnPreDrawListener { if (blurActive()) invalidate(); true }

    init {
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        super.onDetachedFromWindow()
    }

    private fun blurActive() = Theme.canBlur && source?.node != null && material != Material.CARD

    private fun buildPath() {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        path.reset()
        val r = if (capsule) h / 2f else radius
        if (r <= 0f) {
            path.addRect(rect, Path.Direction.CW)
        } else {
            val t = if (roundTop) r else 0f
            val b = if (roundBottom) r else 0f
            path.addRoundRect(rect, floatArrayOf(t, t, t, t, b, b, b, b), Path.Direction.CW)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0 || materialAlpha <= 0f) return
        buildPath()
        val blurred = blurActive() && canvas.isHardwareAccelerated && drawBlur(canvas)

        if (shadow && Build.VERSION.SDK_INT >= 28) {
            canvas.save()
            canvas.clipOutPath(path)
            shadowPaint.setShadowLayer(18f.dp, 0f, 6f.dp, Theme.shadow)
            shadowPaint.color = 0x01000000
            canvas.drawPath(path, shadowPaint)
            canvas.restore()
        }

        fillPaint.color = tint ?: when (material) {
            Material.BAR, Material.BUTTON -> if (blurred) Theme.barFill else Theme.barFillOpaque
            Material.SHEET -> if (blurred) Theme.sheetFill else Theme.sheetFillOpaque
            Material.CARD -> Theme.cardFill
        }
        if (materialAlpha < 1f) fillPaint.color = ColorUtil.alpha(fillPaint.color, materialAlpha)
        canvas.drawPath(path, fillPaint)

        if (rim) {
            val sw = if (Theme.dark) 0.8f.dp else 1f.dp
            rimPaint.strokeWidth = sw
            rimPaint.alpha = 255
            rimPaint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
                intArrayOf(Theme.rimTop, Theme.rimBottom, ColorUtil.alpha(Theme.rimTop, 0.55f)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            rimPaint.alpha = (255 * materialAlpha).toInt()
            canvas.save()
            canvas.clipPath(path)
            canvas.drawPath(path, rimPaint)
            canvas.restore()
        }
        if (hairlineAlpha > 0f) {
            hairPaint.color = ColorUtil.alpha(Theme.separator, hairlineAlpha * materialAlpha)
            canvas.drawRect(0f, height - maxOf(1f, 0.5f.dp), width.toFloat(), height.toFloat(), hairPaint)
        }
    }

    private fun drawBlur(canvas: Canvas): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val src = source?.node ?: return false
        val node = blurNode ?: return false
        val host = source ?: return false
        val r = if (material == Material.SHEET) 32f.dp else 26f.dp
        if (r != effectRadius) {
            val blur = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
            node.setRenderEffect(RenderEffect.createColorFilterEffect(saturate(1.7f), blur))
            effectRadius = r
        }
        getLocationInWindow(myLoc)
        host.getLocationInWindow(srcLoc)
        node.setPosition(0, 0, width, height)
        val rc = node.beginRecording(width, height)
        rc.translate((srcLoc[0] - myLoc[0]).toFloat(), (srcLoc[1] - myLoc[1]).toFloat())
        rc.drawRenderNode(src)
        node.endRecording()
        node.setAlpha(materialAlpha)
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRenderNode(node)
        canvas.restore()
        return true
    }

    private fun saturate(s: Float): ColorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(s) })
}

/** Background for rows of an inset-grouped list: the rows together form one rounded card. */
class GroupedRowBackground(var position: Position, private val radius: Float = 14f.dp) : Drawable() {
    enum class Position { SINGLE, TOP, MIDDLE, BOTTOM }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var pressed = false
    var selected = false
        set(v) { field = v; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b)
        val top = position == Position.SINGLE || position == Position.TOP
        val bottom = position == Position.SINGLE || position == Position.BOTTOM
        val t = if (top) radius else 0f
        val bm = if (bottom) radius else 0f
        path.reset()
        path.addRoundRect(rect, floatArrayOf(t, t, t, t, bm, bm, bm, bm), Path.Direction.CW)
        paint.color = Theme.cardFill
        canvas.drawPath(path, paint)
        if (selected) {
            paint.color = ColorUtil.alpha(Theme.accent, 0.14f)
            canvas.drawPath(path, paint)
        }
        if (pressed) {
            paint.color = Theme.pressed
            canvas.drawPath(path, paint)
        }
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed)
        if (p != pressed) { pressed = p; invalidateSelf(); return true }
        return false
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Rounded rectangle fill, optionally with a pressed overlay. */
class RoundDrawable(
    var color: Int,
    private val radius: Float,
    private val capsule: Boolean = false,
    private val pressedOverlay: Boolean = false,
    var strokeColor: Int = 0,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var pressed = false

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        val r = if (capsule) rect.height() / 2 else radius
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawRoundRect(rect, r, r, paint)
        if (strokeColor != 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f.dp
            paint.color = strokeColor
            rect.inset(0.5f.dp, 0.5f.dp)
            canvas.drawRoundRect(rect, r, r, paint)
        }
        if (pressed) {
            paint.style = Paint.Style.FILL
            paint.color = Theme.pressed
            rect.set(bounds)
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }

    override fun isStateful() = pressedOverlay

    override fun onStateChange(state: IntArray): Boolean {
        val p = pressedOverlay && state.contains(android.R.attr.state_pressed)
        if (p != pressed) { pressed = p; invalidateSelf(); return true }
        return false
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
