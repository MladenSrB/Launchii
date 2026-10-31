package com.oneuiclone.home.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.WindowManager
import java.util.function.Consumer
import kotlin.math.roundToInt

/**
 * Wallpaper blur + Samsung tone curve + dim for each HoneyState (spec §5).
 *
 * One UI applies blur(radius = progress × 250) then a colour curve
 * (minX 15, maxX 255 → minY 53.5, maxY 232) and finally a #A6000000 dim × dimFactor.
 * The linear curve is equivalent to blending a 165-grey at 25.6 % over the blurred wallpaper, which a
 * plain overlay can reproduce exactly on top of the window's cross-window blur.
 */
class BackgroundController(
    private val activity: Activity,
    private val scrim: ScrimView,
    private val wallpaper: WallpaperLayer,
) {

    enum class Kind(val blurFactor: Float, val dim: Float) {
        NONE(0f, 0f),
        APPS(1f, 0.25f),          // config_appsBgDarken 25
        FOLDER(1f, 0.20f),        // config_homeFolderBgDarken 20
        APPS_FOLDER(1f, 0.25f),   // config_appsFolderBgDarken 25
        EDIT(1f, 0.25f),          // config_PageEditBgDarken 25
        DRAG(0.5f, 0f),           // HomeScreen.Drag blur factor 0.5
        WIDGETS(1f, 0.25f),
        QUICK_OPEN(1f, 0.20f),    // the Bubble & Split panel, Home faded out behind it
    }

    private val wm = activity.getSystemService(WindowManager::class.java)
    private var blurEnabled = wm.isCrossWindowBlurEnabled
    private val listener = Consumer<Boolean> { blurEnabled = it; apply() }

    /** Full-strength blur radius in px (tuned against One UI screenshots). */
    var maxRadiusPx = (activity.resources.displayMetrics.widthPixels * 0.16f)

    private var kind = Kind.NONE
    private var progress = 0f
    private var lastRadius = -1

    fun attach() {
        wm.addCrossWindowBlurEnabledListener(listener)
        android.util.Log.i("OneUiHome", "cross-window blur enabled=" + wm.isCrossWindowBlurEnabled)
    }
    fun detach() { wm.removeCrossWindowBlurEnabledListener(listener) }

    /** The state [kind]'s background blends in from (at 1 − progress), e.g. a drag leaving an open folder. */
    private var from = Kind.NONE

    fun set(kind: Kind, progress: Float) {
        this.kind = kind
        this.progress = progress.coerceIn(0f, 1f)
        from = Kind.NONE
        apply()
    }

    /** Part way from [from]'s full background to [to]'s: [f] 0 is all [from], 1 all [to]. */
    fun blend(from: Kind, to: Kind, f: Float) {
        kind = to
        progress = f.coerceIn(0f, 1f)
        this.from = from
        apply()
    }

    val current get() = kind
    val currentProgress get() = progress

    private fun apply() {
        val p = progress
        val q = 1f - p
        val blur = p * kind.blurFactor + q * from.blurFactor
        val dim = p * kind.dim + q * from.dim
        if (wallpaper.available) {
            // launcher-drawn wallpaper: exact One UI pipeline (blur + colour curve), dim on top
            if (lastRadius != 0) { activity.window.setBackgroundBlurRadius(0); lastRadius = 0 }
            wallpaper.maxBlurRadiusPx = maxRadiusPx
            wallpaper.setBlur(blur)
            scrim.curveAlpha = 0f
            scrim.dimAlpha = dim * 0xA6 / 255f
            scrim.invalidate()
            return
        }
        val radius = if (blurEnabled) (blur * maxRadiusPx).roundToInt() else 0
        if (radius != lastRadius) {
            activity.window.setBackgroundBlurRadius(radius)
            lastRadius = radius
        }
        // tone curve only where One UI applies the wallpaper blur view
        scrim.curveAlpha = (p * kind.blurFactor.coerceAtMost(1f) + q * from.blurFactor.coerceAtMost(1f)) * 0.256f
        val fallbackExtra = if (!blurEnabled) 0.2f else 0f
        val shown = p + (if (from != Kind.NONE) q else 0f)
        scrim.dimAlpha = dim * 0xA6 / 255f + shown * fallbackExtra
        scrim.invalidate()
    }
}

/** Full-screen layer drawing the tone-curve grey and the dim. */
class ScrimView(context: Context) : View(context) {
    var curveAlpha = 0f
    var dimAlpha = 0f

    override fun onDraw(canvas: Canvas) {
        if (curveAlpha > 0f) canvas.drawColor(((curveAlpha * 255).roundToInt() shl 24) or 0xA5A5A5)
        if (dimAlpha > 0f) canvas.drawColor(((dimAlpha.coerceAtMost(1f) * 255).roundToInt() shl 24))
    }
}
