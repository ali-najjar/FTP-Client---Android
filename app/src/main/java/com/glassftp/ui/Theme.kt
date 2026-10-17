package com.glassftp.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.TextView
import com.glassftp.App
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/** Apple Human Interface Guidelines system colors, light and dark. */
object Theme {
    var dark = false
        private set
    var density = 3f
        private set

    fun update(ctx: Context) {
        density = ctx.resources.displayMetrics.density
        dark = when (App.prefs.theme) {
            1 -> false
            2 -> true
            else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    private fun c(light: Long, dark: Long) = (if (this.dark) dark else light).toInt()

    val accent get() = c(0xFF007AFF, 0xFF0A84FF)
    val green get() = c(0xFF34C759, 0xFF30D158)
    val red get() = c(0xFFFF3B30, 0xFFFF453A)
    val orange get() = c(0xFFFF9500, 0xFFFF9F0A)
    val yellow get() = c(0xFFFFCC00, 0xFFFFD60A)
    val purple get() = c(0xFFAF52DE, 0xFFBF5AF2)
    val pink get() = c(0xFFFF2D55, 0xFFFF375F)
    val teal get() = c(0xFF30B0C7, 0xFF40C8E0)
    val indigo get() = c(0xFF5856D6, 0xFF5E5CE6)
    val gray get() = c(0xFF8E8E93, 0xFF8E8E93)

    val label get() = c(0xFF000000, 0xFFFFFFFF)
    val secondaryLabel get() = c(0x993C3C43, 0x99EBEBF5)
    val tertiaryLabel get() = c(0x4D3C3C43, 0x4DEBEBF5)
    val separator get() = c(0x4A3C3C43, 0x99545458)
    val fill get() = c(0x1F767680, 0x3D767680)
    val secondaryFill get() = c(0x14767680, 0x29767680)
    val pressed get() = c(0x14000000, 0x1FFFFFFF)

    /** Translucent material of in-content cards (the wallpaper behind is already soft). */
    val cardFill get() = c(0x99FFFFFF, 0x661C1C1E)
    /** Bars and sheets that blur the content behind them. */
    val barFill get() = c(0x99F9F9FB, 0x8C161618)
    val barFillOpaque get() = c(0xF2F7F7FA, 0xF21C1C1E)
    val sheetFill get() = c(0xB8F4F4F7, 0xB0242426)
    val sheetFillOpaque get() = c(0xFAF4F4F7, 0xFA242426)
    val rimTop get() = c(0xE6FFFFFF, 0x59FFFFFF)
    val rimBottom get() = c(0x66FFFFFF, 0x1FFFFFFF)
    val shadow get() = c(0x22000000, 0x66000000)
    val dim get() = c(0x33000000, 0x66000000)
    val knob get() = Color.WHITE
    val segmentSelected get() = c(0xFFFFFFFF, 0xFF636366)

    val canBlur get() = Build.VERSION.SDK_INT >= 31
}

val Int.dp: Int get() = (this * Theme.density + 0.5f).toInt()
val Float.dp: Float get() = this * Theme.density

/** San Francisco is not redistributable; the system sans with HIG sizes reads very close. */
enum class TextStyle(val sp: Float, val weight: Int, val tracking: Float = 0f) {
    LargeTitle(34f, 700, -0.01f),
    Title1(28f, 700, -0.005f),
    Title2(22f, 700),
    Title3(20f, 600),
    Headline(17f, 600),
    Body(17f, 400),
    Callout(16f, 400),
    Subhead(15f, 400),
    Footnote(13f, 400),
    Caption(12f, 400),
    Caption2(11f, 500),
}

object Fonts {
    private val cache = HashMap<Int, Typeface>()

    fun weight(w: Int): Typeface = cache.getOrPut(w) {
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), w, false)
        else when {
            w >= 700 -> Typeface.create("sans-serif", Typeface.BOLD)
            w >= 500 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
    }

    val mono: Typeface by lazy { Typeface.MONOSPACE }
}

fun TextView.style(s: TextStyle, color: Int = Theme.label, weight: Int = s.weight) {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, s.sp)
    typeface = Fonts.weight(weight)
    letterSpacing = s.tracking
    setTextColor(color)
    includeFontPadding = false
}

/** Main-thread dispatcher without kotlinx-coroutines-android. */
object Main : CoroutineDispatcher() {
    private val handler = Handler(Looper.getMainLooper())
    override fun isDispatchNeeded(context: CoroutineContext) = Looper.myLooper() != Looper.getMainLooper()
    override fun dispatch(context: CoroutineContext, block: Runnable) { handler.post(block) }
}

object ColorUtil {
    fun alpha(color: Int, a: Float): Int =
        (((color ushr 24) * a).toInt().coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)

    fun withAlpha(color: Int, a: Int): Int = (a.coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)
}
