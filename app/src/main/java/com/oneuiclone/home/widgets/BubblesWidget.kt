package com.oneuiclone.home.widgets

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserManager
import android.widget.RemoteViews
import android.widget.Toast
import com.oneuiclone.home.AppBubbles
import com.oneuiclone.home.R
import com.oneuiclone.home.icons.IconFactory
import com.oneuiclone.home.model.AppInfo
import com.oneuiclone.home.model.AppKey
import com.oneuiclone.home.settings.Prefs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The Bubbles widget (1×1): opens up to [MAX] chosen apps in bubbles at once, the first one expanded and the rest
 * waiting in the stack. On Launchii's Home it's drawn like an icon, labelled Bubbles: the chosen apps' own icons
 * cascading back from the first ([cascade], drawn by BubblesIconView); other launchers get it as a bitmap ([tile]).
 */
object BubblesWidget {
    const val MAX = 5
    private const val PREFS = "bubbles_widget"
    /** Between bubbles: the notification service drops a burst of more than five posts a second. */
    private const val STAGGER_MS = 300L

    /** The front icon (of the tile) when there's more than one; alone, an app fills the tile like any icon. */
    private const val FRONT = 0.66f
    /** How far (of the tile) the stack behind the front icon reaches up and to the right, ... */
    private const val REACH = 0.30f
    /** ... each step at most this far, ... */
    private const val MAX_STEP = 0.15f
    /** ... each icon this much smaller than the one in front of it. */
    private const val SHRINK = 0.9f

    fun provider(context: Context) = ComponentName(context, BubblesWidgetProvider::class.java)

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The apps widget [appWidgetId] opens, in order. */
    fun apps(context: Context, appWidgetId: Int): List<AppKey> =
        prefs(context).getString(appWidgetId.toString(), null)?.split('\n')?.mapNotNull { AppKey.parse(it) } ?: emptyList()

    fun isConfigured(context: Context, appWidgetId: Int) = prefs(context).contains(appWidgetId.toString())

    fun setApps(context: Context, appWidgetId: Int, apps: List<AppKey>) =
        prefs(context).edit().putString(appWidgetId.toString(), apps.take(MAX).joinToString("\n") { it.flatten() }).apply()

    fun forget(context: Context, appWidgetId: Int) = prefs(context).edit().remove(appWidgetId.toString()).apply()

    /** [key]'s activity, if it's still installed (bubbles only open apps of our own profile). */
    fun resolve(context: Context, key: AppKey): LauncherActivityInfo? {
        val user = context.getSystemService(UserManager::class.java).getUserForSerialNumber(key.userSerial) ?: return null
        if (user != Process.myUserHandle()) return null
        return context.getSystemService(LauncherApps::class.java).getActivityList(key.component.packageName, user)
            .firstOrNull { it.componentName == key.component }
    }

    /**
     * One app in the cascade: its Home icon and the soft silhouette of it ([halo]) that's its shadow, or, for a bare
     * Minimal symbol ([cut]), what it erases from the symbols behind it so it reads cleanly where they overlap. A
     * Stencil icon's [hole] (its cut-out symbol) erases the icons behind it too, so the wallpaper shows through it.
     */
    class Layer(val icon: Bitmap, val halo: Bitmap, val haloOffset: IntArray, val cut: Boolean, val hole: Bitmap? = null)

    /**
     * [icons] (first app first) ready to draw; [minimal] for Minimal symbols, which have no plate to hide those behind;
     * [stencil] for Stencil icons, whose cut-out must not show the icons behind.
     */
    fun layers(icons: List<Bitmap>, minimal: Boolean, stencil: Boolean = false): List<Layer> = icons.take(MAX).map { b ->
        val offset = IntArray(2)
        val halo = b.extractAlpha(Paint().apply {
            maskFilter = android.graphics.BlurMaskFilter(b.width * 0.04f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }, offset)
        // the icon shape less the icon: what's see-through inside it
        val hole = if (!stencil) null else Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ALPHA_8).also {
            val hc = Canvas(it)
            hc.drawPath(IconFactory.maskPath(b.width.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG))
            hc.drawBitmap(b, 0f, 0f, cutPaint)
        }
        Layer(b, halo, offset, minimal, hole)
    }

    /**
     * Where each app sits in a [size]-px tile, first app first: the first at the front, bottom left, each next one
     * smaller and up to the right behind it. Fewer apps step further back, so each one behind shows more of itself;
     * the stack is centred as a whole, and one app alone fills the tile like any icon.
     */
    fun cascade(count: Int, size: Float): List<android.graphics.RectF> {
        if (count == 0) return emptyList()
        val front = size * (if (count == 1) 1f else FRONT)
        val step = if (count == 1) 0f else size * minOf(MAX_STEP, REACH / (count - 1))
        val sizes = FloatArray(count) { front * SHRINK.pow(it) }
        val cx = FloatArray(count) { front / 2f + it * step }
        val cy = FloatArray(count) { size - front / 2f - it * step }
        val dx = (size - (0 until count).maxOf { cx[it] + sizes[it] / 2f }) / 2f
        val dy = -(0 until count).minOf { cy[it] - sizes[it] / 2f } / 2f
        return List(count) {
            android.graphics.RectF(cx[it] - sizes[it] / 2f + dx, cy[it] - sizes[it] / 2f + dy, cx[it] + sizes[it] / 2f + dx, cy[it] + sizes[it] / 2f + dy)
        }
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = 0x47000000 }
    private val cutPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }

    /**
     * Draws the cascade of [layers] in a [size]-px square, back to front; [under] runs just before each icon is drawn
     * (over its shadow), for Frosted icons' glass in that icon's place.
     */
    fun draw(c: Canvas, layers: List<Layer>, size: Float, under: ((android.graphics.RectF) -> Unit)? = null,
             hop: ((Int) -> Float)? = null) {
        val rects = cascade(layers.size, size)
        // a hop (0..1, BubblesIconView's opening feedback) lifts a layer by up to 15 % and grows it by up to 12 %
        if (hop != null) rects.forEachIndexed { i, r ->
            val h = hop(i)
            if (h > 0f) { val grow = r.width() * 0.06f * h; r.inset(-grow, -grow); r.offset(0f, -r.height() * 0.15f * h) }
        }
        val cutting = layers.any { it.cut || it.hole != null }
        // a cut erases only the symbols behind, in a layer of their own, not whatever the canvas already shows
        if (cutting) c.saveLayer(-size * 0.1f, -size * 0.1f, size * 1.1f, size * 1.1f, null)
        for (i in layers.indices.reversed()) {
            val l = layers[i]; val r = rects[i]
            val k = r.width() / l.icon.width
            val o = l.haloOffset
            val halo = android.graphics.RectF(r.left + o[0] * k, r.top + o[1] * k, r.left + (o[0] + l.halo.width) * k, r.top + (o[1] + l.halo.height) * k)
            if (l.cut) {
                // a little larger than the symbol, so a clear gap separates it from those behind
                if (i < layers.size - 1) {
                    c.save(); c.scale(1.08f, 1.08f, r.centerX(), r.centerY()); c.drawBitmap(l.halo, null, halo, cutPaint); c.restore()
                }
            } else {
                halo.offset(0f, r.height() * 0.02f)
                c.drawBitmap(l.halo, null, halo, shadowPaint)
            }
            if (l.hole != null && i < layers.size - 1) c.drawBitmap(l.hole, null, r, cutPaint)
            under?.invoke(r)
            c.drawBitmap(l.icon, null, r, iconPaint)
        }
        if (cutting) c.restore()
    }

    /** The widget before any app is chosen: a white icon (in [mask], other launchers' icon shape) with two bubbles on it. */
    fun emptyTile(size: Int, mask: android.graphics.Path = IconFactory.maskPath(size.toFloat())): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val s = size.toFloat()
        c.drawPath(mask, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFEFEFE.toInt() })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8A5CF5.toInt() }
        c.drawCircle(s * .43f, s * .44f, s * .2f, p)
        c.drawCircle(s * .67f, s * .67f, s * .1f, p)
        return out
    }

    /** The cascade of [icons] (each [size] px, drawn as [look]'s launcher draws icons) as a bitmap, for other launchers. */
    private fun tile(icons: List<Bitmap>, size: Int, look: HostLook): Bitmap {
        if (icons.isEmpty()) return emptyTile(size, look.mask(size))
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val layers = layers(icons, minimal = false)
        draw(Canvas(out), layers, size.toFloat())
        layers.forEach { it.halo.recycle() }
        return out
    }

    /**
     * Opens [apps] in bubbles: the first one expanded, posted last so it's at the front of the stack, the others
     * waiting behind it in order. [onResult] gets each app's place in [apps] once its bubble is up (true) or turned
     * down (false). Without bubble access, says what's missing, starts its setup instead, and returns false.
     */
    fun open(activity: Activity, apps: List<AppInfo>, onResult: ((Int, Boolean) -> Unit)? = null): Boolean {
        if (!AppBubbles.ready(activity)) {
            AppBubbles.missing(activity)?.let { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() }
            AppBubbles.setUp(activity)
            return false
        }
        val context = activity.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val shown = apps.take(MAX)
        shown.reversed().forEachIndexed { i, app ->
            val index = shown.size - 1 - i
            handler.postDelayed({
                AppBubbles.show(context, app, expand = index == 0, onShown = { onResult?.invoke(index, true) }) {
                    Toast.makeText(context, "${app.label} can't open in a bubble", Toast.LENGTH_SHORT).show()
                    onResult?.invoke(index, false)
                }
            }, i * STAGGER_MS)
        }
        return true
    }

    /** An [AppInfo] for [AppBubbles] outside the launcher (its icon is never read there). */
    fun appInfo(context: Context, key: AppKey, info: LauncherActivityInfo) =
        AppInfo(key, info.user, info.label.toString(), Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8), info)

    /**
     * The widget as other launchers show it: the tile and its label, sized like the icons around it, and the apps'
     * icons as that launcher draws them (the system's shape, not Launchii's icon style) ([HostLook]); a tap goes
     * through [BubblesOpenActivity].
     */
    fun updateRemote(context: Context, appWidgetId: Int, options: Bundle? = null) {
        val dpi = context.resources.displayMetrics.densityDpi
        val apps = apps(context, appWidgetId).mapNotNull { resolve(context, it) }
        val tap = PendingIntent.getActivity(context, appWidgetId,
            Intent(context, BubblesOpenActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val views = HostLook.responsive(HostLook.sizes(context, appWidgetId, options = options)) { size ->
            val look = HostLook.of(context, size)
            val icons = apps.take(MAX).map { look.icon(it.getIcon(dpi)) }
            RemoteViews(context.packageName, R.layout.bubbles_widget).apply {
                setImageViewBitmap(R.id.bubbles_tile, tile(icons, look.iconPx, look))
                look.applyIcon(this, R.id.bubbles_tile, R.id.bubbles_label)
                setOnClickPendingIntent(R.id.bubbles_root, tap)
            }.also { icons.forEach(Bitmap::recycle) }
        }
        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, views)
    }
}

class BubblesWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { BubblesWidget.updateRemote(context, it) }
    }

    /** Redrawn whenever the host says how big the widget is (it's placed, the grid changes, the screen turns). */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) =
        BubblesWidget.updateRemote(context, appWidgetId, newOptions)

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { BubblesWidget.forget(context, it) }
    }
}

/**
 * A tap on the widget in another launcher (on Launchii's Home it opens directly): opens its bubbles, or its
 * configuration while no app is chosen or bubbles aren't set up (the setup's permission prompt needs a window).
 * The system only expands a bubble its app posts while in front, so until the first app's bubble is up it stays,
 * as a 1-px window that can't be seen or touched.
 */
class BubblesOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setLayout(1, 1)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val apps = BubblesWidget.apps(this, id).mapNotNull { k -> BubblesWidget.resolve(this, k)?.let { BubblesWidget.appInfo(this, k, it) } }
        if (apps.isEmpty() || !AppBubbles.ready(this)) {
            startActivity(Intent(this, BubblesConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            finish()
            return
        }
        BubblesWidget.open(this, apps) { index, _ -> if (index == 0) finish() }
        window.decorView.postDelayed({ finish() }, GIVE_UP_MS)
    }

    private companion object {
        /** Past the last bubble's post and its check, in case the first one never reports. */
        const val GIVE_UP_MS = 4000L
    }
}
