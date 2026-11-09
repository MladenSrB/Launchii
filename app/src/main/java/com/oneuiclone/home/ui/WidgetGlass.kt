package com.oneuiclone.home.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * The wallpaper as One UI's blurred widget backgrounds show it: a quarter-resolution copy of the Home wallpaper
 * (window coordinates), blurred and graded with Samsung's blur curve. Widgets set to Blur draw the part of it behind
 * their background (WidgetManager.RoundedWidgetView.glass), so it lines up with the real wallpaper wherever they move.
 */
class WidgetGlass {
    /** Blurred copy; null while it's built or when the wallpaper can't be read (widgets are then just translucent). */
    var bitmap: Bitmap? = null; private set
    /** Window pixels per bitmap pixel. */
    var scale = 1f; private set
    /** Widgets drawing the glass, redrawn when it changes. */
    val views = HashSet<View>()

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "widget-glass").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0

    /** Rebuild from [wallpaper]'s copy (call after each reload). */
    fun rebuild(wallpaper: WallpaperLayer) =
        build(wallpaper.width, wallpaper.height, wallpaper.available) { wallpaper.drawSnapshot(it) }

    /**
     * Rebuild from a display-sized wallpaper image ([WallpaperLayer.drawSnapshot]'s framing), for windows that
     * draw Home themselves (the Wallpaper and style preview).
     */
    fun rebuild(full: Bitmap) = build(full.width, full.height, true) { WallpaperLayer.drawFramed(it, full); true }

    private fun build(w: Int, h: Int, available: Boolean, draw: (Canvas) -> Boolean) {
        val gen = ++generation
        if (!available || w == 0 || h == 0 || io.isShutdown) { publish(gen, null, 1f); return }
        io.execute {
            val out = try { blurred(w, h, draw) } catch (e: Exception) {
                android.util.Log.w("OneUiHome", "widget glass failed", e)
                null
            }
            main.post { publish(gen, out, if (out == null) 1f else w / out.width.toFloat()) }
        }
    }

    private fun publish(gen: Int, b: Bitmap?, s: Float) {
        if (gen != generation) return
        bitmap = b; scale = s
        views.forEach { it.invalidate() }
    }

    fun destroy() { io.shutdownNow() }

    companion object {
        /** Blur strength as a fraction of the Apps screen's full blur. */
        const val LEVEL = 0.6f

        /**
         * The glass for a [w]×[h] window whose wallpaper [draw] paints (false when it can't): quarter size, blurred
         * and graded. Window pixels per bitmap pixel are w / its width.
         */
        fun blurred(w: Int, h: Int, draw: (Canvas) -> Boolean): Bitmap? {
            val small = Bitmap.createBitmap(max(1, w / WallpaperLayer.DOWNSCALE), max(1, h / WallpaperLayer.DOWNSCALE), Bitmap.Config.ARGB_8888)
            val c = Canvas(small)
            c.scale(small.width / w.toFloat(), small.height / h.toFloat())
            if (!draw(c)) return null
            val blurred = WallpaperBlur.boxBlur(small, LEVEL * w * 0.16f / WallpaperLayer.DOWNSCALE)
            return Bitmap.createBitmap(blurred.width, blurred.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawBitmap(blurred, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(WallpaperLayer.curveMatrix()) })
            }
        }
    }
}
