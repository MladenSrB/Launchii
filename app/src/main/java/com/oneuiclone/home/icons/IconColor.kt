package com.oneuiclone.home.icons

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/** Directional colors from the icon's outer opaque contour, without sampling its centre artwork. */
object IconColor {
    class EdgeColors(val texture: Bitmap, val average: Int)
    private val cache = WeakHashMap<Bitmap, EdgeColors>()
    private const val SIZE = 64
    private const val SAMPLES = 128

    @Synchronized
    fun edgeExtension(icon: Bitmap?, fallback: Int): EdgeColors {
        if (icon == null || icon.isRecycled) return solid(fallback)
        cache[icon]?.let { return it }
        return try {
            val software = if (icon.config == Bitmap.Config.HARDWARE) icon.copy(Bitmap.Config.ARGB_8888, false) else icon
            val small = Bitmap.createScaledBitmap(software, SIZE, SIZE, true)
            val pixels = IntArray(SIZE * SIZE)
            small.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            if (small !== software) small.recycle()
            if (software !== icon) software.recycle()
            val ring = IntArray(SAMPLES)
            for (i in ring.indices) {
                val angle = i * 2.0 * PI / SAMPLES
                val dx = cos(angle); val dy = sin(angle)
                var first = -1
                var count = 0; var red = 0; var green = 0; var blue = 0
                // Walk in from outside the actual alpha contour. Average only its outer three pixels.
                for (distance in 48 downTo 0) {
                    val x = (31.5 + dx * distance).toInt()
                    val y = (31.5 + dy * distance).toInt()
                    if (x !in 0 until SIZE || y !in 0 until SIZE) continue
                    val c = pixels[y * SIZE + x]
                    if (Color.alpha(c) >= 224) {
                        if (first < 0) first = distance
                        if (first - distance > 2) break
                        count++; red += Color.red(c); green += Color.green(c); blue += Color.blue(c)
                    } else if (first >= 0) break
                }
                if (count > 0) ring[i] = Color.rgb(red / count, green / count, blue / count)
            }
            // Transparent/plateless icons can have empty rays. Fill from adjacent contour samples.
            val sampled = ring.copyOf()
            for (i in ring.indices) if (ring[i] == 0) {
                ring[i] = (1 until SAMPLES).firstNotNullOfOrNull { d ->
                    sampled[(i + d) % SAMPLES].takeIf { it != 0 }
                        ?: sampled[(i - d + SAMPLES) % SAMPLES].takeIf { it != 0 }
                } ?: (fallback or 0xFF000000.toInt())
            }
            val smooth = IntArray(SAMPLES) { i ->
                val neighbors = (-2..2).map { ring[(i + it + SAMPLES) % SAMPLES] }
                Color.rgb(neighbors.sumOf { Color.red(it) } / 5,
                    neighbors.sumOf { Color.green(it) } / 5, neighbors.sumOf { Color.blue(it) } / 5)
            }
            val average = Color.rgb(smooth.sumOf { Color.red(it) } / SAMPLES,
                smooth.sumOf { Color.green(it) } / SAMPLES, smooth.sumOf { Color.blue(it) } / SAMPLES)
            val field = IntArray(SIZE * SIZE) { index ->
                val x = (index % SIZE - 31.5f) / 31.5f
                val y = (index / SIZE - 31.5f) / 31.5f
                val angle = ((atan2(y, x) / (2 * PI) + 1) % 1) * SAMPLES
                val lo = floor(angle).toInt() % SAMPLES
                val edge = ColorUtils.blendARGB(smooth[lo], smooth[(lo + 1) % SAMPLES], (angle - floor(angle)).toFloat())
                ColorUtils.blendARGB(average, edge, hypot(x, y).coerceAtMost(1f))
            }
            EdgeColors(Bitmap.createBitmap(field, SIZE, SIZE, Bitmap.Config.ARGB_8888), average)
                .also { cache[icon] = it }
        } catch (_: Exception) { solid(fallback) }
    }

    private fun solid(color: Int): EdgeColors {
        val opaque = color or 0xFF000000.toInt()
        return EdgeColors(Bitmap.createBitmap(intArrayOf(opaque), 1, 1, Bitmap.Config.ARGB_8888), opaque)
    }
}
