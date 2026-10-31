package com.oneuiclone.home.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Settings › Blur wallpaper, baked in: the Home screen wallpaper itself is replaced by a blurred copy (the lock
 * screen keeps the sharp one), so nothing blurs at runtime and the system's own app-close animation shows it
 * blurred too. The sharp original is kept to re-blur at another strength, or to put back when it's turned off.
 */
object WallpaperBlur {

    enum class Result { DONE, UNCHANGED, NO_ACCESS }

    private const val SHARP_FILE = "wallpaper_sharp.png"
    private const val KEY_BLURRED_ID = "blurred_id"
    private const val KEY_LEVEL = "blurred_level"

    /** One worker for the whole process: Settings and the launcher never write the wallpaper at the same time. */
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "wallpaper-blur").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    /**
     * Last system wallpaper a sync looked at, with the launcher copy's id then, so one that can't be read isn't
     * retried on every colours change, but is once the launcher records its copy (after the change, when it set it).
     */
    @Volatile private var lastSeen = -1 to -1

    /**
     * Makes the Home wallpaper match [level] (0 = sharp, 1 = the Apps screen's full blur).
     * [onlyIfReplaced]: launcher checks; only acts when the wallpaper was replaced from elsewhere while blur is on.
     */
    fun apply(context: Context, level: Float, onlyIfReplaced: Boolean = false, done: (Result) -> Unit) {
        val app = context.applicationContext
        io.execute {
            val r = try {
                run(app, level, onlyIfReplaced)
            } catch (e: Exception) {
                android.util.Log.w("OneUiHome", "wallpaper blur failed", e)
                Result.NO_ACCESS
            }
            main.post { done(r) }
        }
    }

    private fun run(context: Context, level: Float, onlyIfReplaced: Boolean): Result {
        val wm = WallpaperManager.getInstance(context)
        val prefs = context.getSharedPreferences(WallpaperLayer.PREFS, Context.MODE_PRIVATE)
        val current = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
        val ours = prefs.getInt(KEY_BLURRED_ID, -1).let { it > 0 && it == current }
        val seen = current to prefs.getInt(WallpaperLayer.KEY_SYSTEM_ID, -1)
        if (onlyIfReplaced && (level <= 0f || ours || seen == lastSeen)) return Result.UNCHANGED
        val sharpFile = File(context.filesDir, SHARP_FILE)

        if (level <= 0f) {
            if (ours) {
                val sharp = BitmapFactory.decodeFile(sharpFile.path) ?: return Result.NO_ACCESS
                setHome(context, wm, sharp)
            }
            prefs.edit().remove(KEY_BLURRED_ID).remove(KEY_LEVEL).commit()
            return if (ours) Result.DONE else Result.UNCHANGED
        }
        if (ours && prefs.getFloat(KEY_LEVEL, -1f) == level) return Result.UNCHANGED
        // re-blur from the kept original, never from the blurred one
        val sharp = if (ours) BitmapFactory.decodeFile(sharpFile.path) ?: return Result.NO_ACCESS
            else readCurrent(context, prefs, current)?.also { b ->
                sharpFile.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } ?: run { lastSeen = seen; return Result.NO_ACCESS }
        val shared = wm.getWallpaperId(WallpaperManager.FLAG_LOCK) <= 0
        val id = setHome(context, wm, blur(sharp, level))
        // a lock screen that showed Home's wallpaper keeps the sharp one (for the Wallpaper and style preview)
        if (shared) WallpaperLayer.recordLockCopy(context, sharp)
        prefs.edit().putInt(KEY_BLURRED_ID, id).putFloat(KEY_LEVEL, level).commit()
        return Result.DONE
    }

    /** The current Home wallpaper at display size: the launcher's own copy (a wallpaper set elsewhere can't be read). */
    private fun readCurrent(context: Context, prefs: android.content.SharedPreferences, current: Int): Bitmap? {
        val own = File(context.filesDir, WallpaperLayer.OWN_FILE)
        return if (prefs.getInt(WallpaperLayer.KEY_SYSTEM_ID, -1) == current && own.exists()) BitmapFactory.decodeFile(own.path) else null
    }

    /** Sets [bmp] as the Home wallpaper only and records it as the launcher's exact copy (for the open/close zoom). */
    private fun setHome(context: Context, wm: WallpaperManager, bmp: Bitmap): Int =
        WallpaperLayer.recordOwnCopy(context, bmp) { wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM) }

    /**
     * The Apps screen's look at [level]: blurred at quarter resolution (as [WallpaperLayer] does) with RenderEffect's
     * radius → sigma, by three box passes (≈ Gaussian), then scaled back up.
     */
    private fun blur(src: Bitmap, level: Float): Bitmap {
        val w = max(1, src.width / WallpaperLayer.DOWNSCALE)
        val h = max(1, src.height / WallpaperLayer.DOWNSCALE)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val radius = level * src.width * 0.16f / WallpaperLayer.DOWNSCALE        // BackgroundController.maxRadiusPx
        return Bitmap.createScaledBitmap(boxBlur(small, radius), src.width, src.height, true)
    }

    /** [src] blurred as RenderEffect's [radius] (px of [src]) would: radius → sigma, three box passes (≈ Gaussian). */
    fun boxBlur(src: Bitmap, radius: Float): Bitmap {
        val w = src.width
        val h = src.height
        val sigma = radius * 0.57735f + 0.5f
        val box = ((sqrt(4f * sigma * sigma + 1f) - 1f) / 2f).roundToInt().coerceAtLeast(1)
        val a = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        val b = IntArray(w * h)
        repeat(3) {
            boxRowsTransposed(a, b, w, h, box)
            boxRowsTransposed(b, a, h, w, box)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(a, 0, w, 0, 0, w, h)
        return out
    }

    /** Box-blurs each row of [src] (w×h) with radius [r], edges clamped, writing it transposed into [dst] (h×w). */
    private fun boxRowsTransposed(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = src[row + i.coerceIn(0, w - 1)]
                sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (x in 0 until w) {
                dst[x * h + y] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val out = src[row + (x - r).coerceIn(0, w - 1)]
                val inn = src[row + (x + r + 1).coerceIn(0, w - 1)]
                sa += (inn ushr 24) - (out ushr 24)
                sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                sb += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }
}
