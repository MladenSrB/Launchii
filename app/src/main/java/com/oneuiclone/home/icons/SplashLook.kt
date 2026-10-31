package com.oneuiclone.home.icons

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * What an app's splash screen looks like, so the Zoom In open can end on it. Android 12+ fills the starting
 * window with the theme's windowSplashScreenBackground, or failing that the window background's dominant colour
 * (WM Shell SplashscreenContentDrawer), and puts the icon in a 160 dp circle in the middle.
 */
class SplashLook(private val context: Context) {
    /**
     * [color] is the splash background (opaque). A [translucent] activity never covers Home (and shows no splash),
     * so it gets no card. [source] says where the colour came from (for the animation log).
     */
    class Look(val color: Int, val translucent: Boolean, val source: String)

    private val looks = ConcurrentHashMap<String, Look>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "splash-look").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())

    private fun night() = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun key(info: LauncherActivityInfo) = info.componentName.flattenToShortString() + if (night()) "/night" else "/day"

    /** Cached per activity and light/dark; [prefetch] fills the cache ahead of taps. */
    fun look(info: LauncherActivityInfo): Look = looks.getOrPut(key(info)) { resolve(info) }

    fun isCached(info: LauncherActivityInfo) = looks.containsKey(key(info))

    /**
     * The background of the app's ordinary screens, for a Zoom In from one of its widgets: a widget opens a screen
     * inside the app (a contact, a forecast), which starts on the app theme's window background, not on the
     * launcher activity's splash colour (often a brand colour). Never [Look.translucent]: where a widget's click
     * goes isn't known.
     */
    fun surfaceLook(info: LauncherActivityInfo): Look = looks.getOrPut(key(info) + "/surface") { resolveSurface(info) }

    private fun resolveSurface(info: LauncherActivityInfo): Look {
        val fallback = if (night()) Color.BLACK else Color.WHITE
        return try {
            val ai = info.activityInfo
            val pkg = context.createPackageContext(ai.packageName, Context.CONTEXT_RESTRICTED)
            val theme = pkg.resources.newTheme()
            val themeRes = ai.applicationInfo.theme.takeIf { it != 0 } ?: ai.themeResource.takeIf { it != 0 }
                ?: android.R.style.Theme_DeviceDefault_DayNight
            theme.applyStyle(themeRes, true)
            val themeName = try { pkg.resources.getResourceEntryName(themeRes) } catch (_: Exception) { "#" + Integer.toHexString(themeRes) }
            val bg = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground)).let {
                val d = try { it.getDrawable(0) } catch (_: Exception) { null }; it.recycle(); d
            }
            bg?.let { dominant(it) }?.let { return Look(it, false, "app window background of $themeName (${bg.javaClass.simpleName})") }
            val cb = theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground)).let {
                val c = it.getColor(0, Color.TRANSPARENT); it.recycle(); c
            }
            if (Color.alpha(cb) == 255) Look(cb, false, "app colorBackground of $themeName")
            else Look(fallback, false, "default, app theme $themeName has no usable background")
        } catch (e: Exception) {
            Look(fallback, false, "default, theme unreadable: $e")
        }
    }

    fun prefetch(infos: List<LauncherActivityInfo>) {
        val todo = infos.filter { !isCached(it) }
        if (todo.isEmpty()) return
        worker.execute { for (i in todo) try { look(i) } catch (_: Exception) {} }
    }

    /** The app's own icon (not themed), loaded off the main thread at a density sharp enough for the 160 dp circle. */
    fun loadIcon(info: LauncherActivityInfo, done: (Drawable) -> Unit) = worker.execute {
        val d = try { info.getIcon(android.util.DisplayMetrics.DENSITY_XXXHIGH) } catch (_: Exception) { null } ?: return@execute
        main.post { done(d) }
    }

    private fun resolve(info: LauncherActivityInfo): Look {
        val fallback = if (night()) Color.BLACK else Color.WHITE
        return try {
            val ai = info.activityInfo
            val pkg = context.createPackageContext(ai.packageName, Context.CONTEXT_RESTRICTED)
            val theme = pkg.resources.newTheme()
            theme.applyStyle(ai.themeResource.takeIf { it != 0 } ?: android.R.style.Theme_DeviceDefault_DayNight, true)
            fun flag(attr: Int) = theme.obtainStyledAttributes(intArrayOf(attr)).let { val b = it.getBoolean(0, false); it.recycle(); b }
            val translucent = flag(android.R.attr.windowIsTranslucent) || flag(android.R.attr.windowIsFloating)
            val splash = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowSplashScreenBackground)).let {
                val c = it.getColor(0, Color.TRANSPARENT); it.recycle(); c
            }
            val themeName = try { pkg.resources.getResourceEntryName(ai.themeResource) } catch (_: Exception) { "#" + Integer.toHexString(ai.themeResource) }
            if (Color.alpha(splash) != 0) return Look(splash or 0xFF000000.toInt(), translucent, "splash background of $themeName")
            val bg = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground)).let {
                val d = try { it.getDrawable(0) } catch (_: Exception) { null }; it.recycle(); d
            }
            val c = bg?.let { dominant(it) }
            if (c != null) Look(c, translucent, "window background of $themeName (${bg.javaClass.simpleName})")
            else Look(fallback, translucent, "default, $themeName has no usable background")
        } catch (e: Exception) {
            Look(fallback, false, "default, theme unreadable: $e")
        }
    }

    /** A plain colour, or the most common colour of the drawable drawn small (a translucent background counts as none). */
    private fun dominant(d: Drawable): Int? {
        if (d is ColorDrawable) return d.color.takeIf { Color.alpha(it) == 255 }
        val n = 24
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, n, n)
        d.draw(Canvas(bmp))
        val px = IntArray(n * n)
        bmp.getPixels(px, 0, n, 0, 0, n, n)
        bmp.recycle()
        val buckets = HashMap<Int, IntArray>()                        // 4 bits per channel → count, r, g, b sums
        for (c in px) {
            if (Color.alpha(c) < 250) continue
            val b = (Color.red(c) shr 4 shl 8) or (Color.green(c) shr 4 shl 4) or (Color.blue(c) shr 4)
            val s = buckets.getOrPut(b) { IntArray(4) }
            s[0]++; s[1] += Color.red(c); s[2] += Color.green(c); s[3] += Color.blue(c)
        }
        val top = buckets.values.maxByOrNull { it[0] } ?: return null
        if (top[0] < px.size / 4) return null
        return Color.rgb(top[1] / top[0], top[2] / top[0], top[3] / top[0])
    }
}
