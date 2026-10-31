package com.oneuiclone.home.settings

import android.app.Activity
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.text.format.DateFormat
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.widget.FrameLayout
import com.oneuiclone.home.Launcher
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.icons.IconFactory
import com.oneuiclone.home.model.AppKey
import com.oneuiclone.home.model.AppRepository
import com.oneuiclone.home.model.Container
import com.oneuiclone.home.model.HomeItem
import com.oneuiclone.home.model.WorkspaceModel
import com.oneuiclone.home.ui.CellLayout
import com.oneuiclone.home.ui.FolderIconView
import com.oneuiclone.home.ui.Fonts
import com.oneuiclone.home.ui.HomeSearchButton
import com.oneuiclone.home.ui.IconView
import com.oneuiclone.home.ui.LargeFolderIconView
import com.oneuiclone.home.ui.PageIndicator
import com.oneuiclone.home.ui.WallpaperLayer
import com.oneuiclone.home.ui.WidgetGlass
import com.oneuiclone.home.widgets.WidgetStyles
import java.util.concurrent.Executors

/**
 * Wallpaper and style's previews. Home's first page is built from Home's own views (IconView, folders, the dock,
 * the search button and dots) with Home's own [DeviceProfile] at the full display size, off screen, and drawn
 * scaled down, so it's the real layout with the [Look] being edited. Widgets are copies of the running Home's.
 *
 * Wallpapers come from the copies the launcher keeps of ones it set (it can't read the system's); anything else
 * shows as a gradient of the wallpaper's colours.
 */
class StylePreview(private val activity: Activity) {

    /** The icon settings being previewed (sliders preview before they're saved). */
    data class Look(val iconScale: Int, val themed: Boolean, val themeAll: Boolean, val plateAlpha: Float, val labels: Boolean, val dark: Boolean,
                    val minimal: Boolean, val look: String, val mode: String) {
        companion object {
            /** The saved settings, with any slider being dragged in their place. */
            fun of(prefs: Prefs, iconScale: Int = prefs.iconScale, transparency: Int = prefs.glassIconTransparency) = Look(
                iconScale = iconScale,
                themed = prefs.themedIcons,
                themeAll = prefs.themeAllApps,
                plateAlpha = if (prefs.themedIcons && prefs.glassIcons) 1f - transparency / 100f else 1f,
                labels = prefs.homeLabels,
                dark = prefs.darkIcons,
                minimal = prefs.minimalIcons,
                look = prefs.iconLook,
                mode = prefs.iconMode,
            )
        }
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val iconFactory = IconFactory(activity)
    private val prefs = Prefs(activity)
    private val glass = WidgetGlass()

    /** Display size, portrait, and the system bars Home lays out around. */
    val width: Int
    val height: Int
    private val insets: Rect

    init {
        val m = activity.windowManager.maximumWindowMetrics
        width = minOf(m.bounds.width(), m.bounds.height())
        height = maxOf(m.bounds.width(), m.bounds.height())
        val i = m.windowInsets.getInsets(WindowInsets.Type.systemBars())
        insets = Rect(i.left, i.top, i.right, i.bottom)
    }

    private class Loaded(
        val items: List<HomeItem>,
        val folders: Map<Long, List<AppKey>>,
        val pages: Int,
        val drawables: Map<AppKey, Pair<String, Drawable>>,
        val home: Bitmap?,
        val lock: Bitmap?,
        val homeColors: WallpaperColors?,
        val lockColors: WallpaperColors?,
    )

    @Volatile private var loaded: Loaded? = null
    private var widgetShots: Map<Int, Bitmap> = emptyMap()
    private var generation = 0
    /** Icons last made, and for which size and look, so a label-only change reuses them. */
    private var icons: Map<AppKey, Bitmap> = emptyMap()
    private var iconsFor: Any? = null

    /** (Re)reads the wallpapers and Home's first page, then calls [done] on the main thread. */
    fun load(done: () -> Unit) {
        if (io.isShutdown) return
        io.execute {
            val l = try { read() } catch (e: Exception) {
                android.util.Log.w("OneUiHome", "style preview: load failed", e); null
            }
            main.post {
                if (io.isShutdown || l == null) return@post
                loaded = l
                iconsFor = null
                widgetShots = Launcher.live?.get()?.let { home ->
                    l.items.filterIsInstance<HomeItem.Widget>().mapNotNull { w -> home.widgetSnapshot(w.appWidgetId)?.let { w.appWidgetId to it } }.toMap()
                } ?: emptyMap()
                glass.rebuild(l.home ?: gradient(l.homeColors))
                done()
            }
        }
    }

    private fun read(): Loaded {
        val repo = AppRepository(activity, iconFactory, 1)
        val model = WorkspaceModel(activity, repo)
        val ok = model.load()
        repo.destroy()
        val first = if (!ok) emptyList() else model.items.filter {
            it.container == Container.HOTSEAT || (it.container == Container.WORKSPACE && it.screen == 0)
        }
        val folders = first.filterIsInstance<HomeItem.Folder>().associate { f -> f.id to model.appsInFolder(f.id).map { it.key } }
        val keys = first.filterIsInstance<HomeItem.App>().map { it.key } + folders.values.flatten()

        val launcherApps = activity.getSystemService(LauncherApps::class.java)
        val users = activity.getSystemService(UserManager::class.java)
        val dpi = activity.resources.displayMetrics.densityDpi
        val drawables = HashMap<AppKey, Pair<String, Drawable>>()
        for (k in keys.distinct()) {
            val user = users.getUserForSerialNumber(k.userSerial) ?: continue
            val info = try { launcherApps.resolveActivity(Intent(Intent.ACTION_MAIN).setComponent(k.component), user) } catch (_: Exception) { null } ?: continue
            val raw = info.getIcon(dpi)
            val d = if (user == android.os.Process.myUserHandle()) raw else activity.packageManager.getUserBadgedIcon(raw, user)
            drawables[k] = info.label.toString() to d
        }

        val wm = WallpaperManager.getInstance(activity)
        val home = if (WallpaperLayer.hasOwnCopy(activity)) decode(java.io.File(activity.filesDir, WallpaperLayer.OWN_FILE)) else null
        // no separate lock wallpaper: the lock screen shows Home's (unblurred, as a blurred Home always is separate)
        val lock = if (wm.getWallpaperId(WallpaperManager.FLAG_LOCK) <= 0) home
            else WallpaperLayer.lockCopyFile(activity)?.let { decode(it) }
        val homeColors = try { wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) } catch (_: Exception) { null }
        val lockColors = try { wm.getWallpaperColors(WallpaperManager.FLAG_LOCK) } catch (_: Exception) { null } ?: homeColors
        val pages = maxOf(model.pageCount, (model.items.filter { it.container == Container.WORKSPACE }.maxOfOrNull { it.screen } ?: 0) + 1)
        return Loaded(first, folders, pages, drawables, home, lock, homeColors, lockColors)
    }

    private fun decode(f: java.io.File): Bitmap? = BitmapFactory.decodeFile(f.path)?.let {
        if (it.width == width && it.height == height) it else WallpaperLayer.centerCrop(it, width, height)
    }

    /** A wallpaper the launcher can't read (set elsewhere, or live): its colours, top to bottom. */
    private fun gradient(c: WallpaperColors?): Bitmap {
        val cols = listOfNotNull(c?.primaryColor, c?.secondaryColor, c?.tertiaryColor).map { it.toArgb() }
            .ifEmpty { listOf(0xFF3A4A6B.toInt(), 0xFF1C2333.toInt()) }
        val b = Bitmap.createBitmap(width / 4, height / 4, Bitmap.Config.ARGB_8888)
        val p = Paint().apply {
            shader = LinearGradient(0f, 0f, b.width * 0.3f, b.height.toFloat(),
                if (cols.size == 1) intArrayOf(cols[0], cols[0]) else cols.toIntArray(), null, Shader.TileMode.CLAMP)
        }
        Canvas(b).drawPaint(p)
        return Bitmap.createScaledBitmap(b, width, height, true)
    }

    /** Height of the dock strip ([render]) at [outWidth] wide: the dock's band of Home, full width. */
    fun dockHeight(outWidth: Int): Int = (outWidth * profile(100).hotseatRect.height() / width.toFloat()).toInt()

    private fun profile(iconScale: Int) = DeviceProfile(width, height, activity.resources.displayMetrics.density, insets,
        homeCols = prefs.homeCols, homeRows = prefs.homeRows, appsCols = prefs.appsCols, appsRows = prefs.appsRows,
        iconScale = iconScale / 100f)

    /**
     * Draws Home with [look] at [homeWidth] wide, and its dock band at [dockWidth] (the first row when the dock is
     * empty), and hands both to [done] (main thread); a newer request supersedes it. Icons are made off the main
     * thread, the views drawn on it.
     */
    fun render(look: Look, homeWidth: Int, dockWidth: Int, done: (home: Bitmap, dock: Bitmap) -> Unit) {
        val l = loaded ?: return
        val gen = ++generation
        val dp = profile(look.iconScale)
        val key = listOf(dp.iconSizePx, look.themed, look.themeAll, look.plateAlpha, look.dark, look.minimal, look.look, look.mode, iconFactory.isNight())
        val draw = { made: Map<AppKey, Bitmap> ->
            if (gen == generation) drawHome(l, dp, look, made, homeWidth, dockWidth).let { (h, d) -> done(h, d) }
        }
        if (key == iconsFor) { draw(icons); return }
        io.execute {
            val night = iconFactory.isNight()
            val made = l.drawables.mapValues { (k, v) ->
                iconFactory.homeIcon(v.second, dp.iconSizePx, night, look.themed, look.themeAll, look.plateAlpha, look.dark, k.component.packageName, look.minimal, look.look, look.mode).first
            }
            main.post {
                if (io.isShutdown) return@post
                icons = made; iconsFor = key
                draw(made)
            }
        }
    }

    private fun drawHome(l: Loaded, dp: DeviceProfile, look: Look, made: Map<AppKey, Bitmap>, homeWidth: Int, dockWidth: Int): Pair<Bitmap, Bitmap> {
        val bright = l.homeColors != null && (l.homeColors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
        val root = FrameLayout(activity)
        val page = CellLayout(activity, dp.homeCols, dp.homeRows).apply {
            setPadding(dp.workspaceGrid.left, dp.workspaceGrid.top, dp.widthPx - dp.workspaceGrid.right, dp.heightPx - dp.workspaceGrid.bottom)
        }
        root.addView(page, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val dock = l.items.filter { it.container == Container.HOTSEAT }.sortedBy { it.x }
        val hotseat = CellLayout(activity, dock.size.coerceIn(1, 7), 1)
        root.addView(hotseat, FrameLayout.LayoutParams(dp.hotseatRect.width(), dp.hotseatRect.height()).apply {
            leftMargin = dp.hotseatRect.left; topMargin = dp.hotseatRect.top
        })

        fun itemView(item: HomeItem): View? = when (item) {
            is HomeItem.App -> {
                val icon = made[item.key] ?: return null
                IconView(activity, dp).apply {
                    this.icon = icon
                    label = l.drawables[item.key]?.first ?: ""
                    if (look.themed && (look.plateAlpha < 1f || look.look == IconFactory.LOOK_STENCIL)) plateGlass = glass
                }
            }
            is HomeItem.Folder -> {
                val content = l.folders[item.id].orEmpty().mapNotNull { k -> made[k]?.let { it to k } }
                (if (item.large) LargeFolderIconView(activity, dp).apply { apps = content.map { it.first to it.second } }
                    else FolderIconView(activity, dp).apply { previews = content.map { it.first } }).apply {
                    label = item.title
                    if (item.color != 0) bgColor = item.color
                    val t = WidgetStyles.folderTransparency(activity, item.id)
                    fillOpacity = if (t == null) 1f else 1f - t / 100f
                    glass = if (t == null) null else this@StylePreview.glass
                }
            }
            is HomeItem.Widget -> ShotView(activity, widgetShots[item.appWidgetId], dp.dp(20f))
        }
        for (item in l.items) {
            val v = itemView(item) ?: continue
            (v as? IconView)?.darkText = bright
            if (item.container == Container.HOTSEAT) {
                (v as? IconView)?.showLabel = false
                hotseat.addCell(v, dock.indexOf(item), 0)
            } else {
                (v as? IconView)?.showLabel = look.labels
                page.addCell(v, item.x, item.y, item.spanX, item.spanY)
            }
        }

        // the search button with the page dots beside it, as Launcher.buildViews places them
        val search = HomeSearchButton(activity, dp, activity.getString(com.oneuiclone.home.R.string.search_hint)).apply {
            glassTransparency = WidgetStyles.FOLDER_DEFAULT_TRANSPARENCY / 100f
            glass = this@StylePreview.glass
        }
        val indicator = PageIndicator(activity, dp.indicatorSlotPx * 0.7f, dp.indicatorDotPx).apply {
            opacity = 0.8f; count = l.pages; darkDots = bright
            leadingGapPx = dp.dp(6f)
            onLeadingMoved = { x -> search.translationX = x }
            leadingPx = search.pillWidth.toFloat()
            setPage(0, false)
        }
        root.addView(indicator, FrameLayout.LayoutParams(MATCH_PARENT, dp.indicatorRect.height()).apply { topMargin = dp.indicatorRect.top })
        root.addView(search, FrameLayout.LayoutParams(search.pillWidth, search.pillHeight).apply {
            topMargin = dp.indicatorRect.top + (dp.indicatorRect.height() - search.pillHeight) / 2
        })

        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val wall = l.home ?: gradient(l.homeColors)
        val screenW = width.toFloat()
        val home = Bitmap.createBitmap(homeWidth, (homeWidth * height / width.toFloat()).toInt(), Bitmap.Config.ARGB_8888)
        Canvas(home).apply {
            scale(homeWidth / screenW, homeWidth / screenW)
            WallpaperLayer.drawFramed(this, wall)
            root.draw(this)
        }
        // the dock's band, or the first row's (centred on it, as tall) when there's no dock
        val bandH = dp.hotseatRect.height()
        val bandTop = if (dock.isNotEmpty()) dp.hotseatRect.top else dp.workspaceGrid.top + dp.cellHeight / 2 - bandH / 2
        val dockOut = Bitmap.createBitmap(dockWidth, dockHeight(dockWidth), Bitmap.Config.ARGB_8888)
        Canvas(dockOut).apply {
            scale(dockWidth / screenW, dockWidth / screenW)
            translate(0f, -bandTop.toFloat())
            WallpaperLayer.drawFramed(this, wall)
            root.draw(this)
        }
        return home to dockOut
    }

    /** The lock screen: its wallpaper under a large stacked clock and the date. */
    fun renderLock(outWidth: Int): Bitmap? {
        val l = loaded ?: return null
        val out = Bitmap.createBitmap(outWidth, (outWidth * height / width.toFloat()).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val k = outWidth / width.toFloat()
        c.scale(k, k)
        c.drawBitmap(l.lock ?: gradient(l.lockColors), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        val d = activity.resources.displayMetrics.density
        val bright = l.lockColors != null && (l.lockColors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
        val ink = if (bright) 0xFF1B1B1F.toInt() else 0xFFFFFFFF.toInt()
        val shadow = if (bright) 0 else 0x40000000
        val now = java.util.Date()
        val clock = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; textAlign = Paint.Align.CENTER; typeface = Fonts.ui(activity, 300)
            textSize = width * 0.36f
            letterSpacing = -0.04f
            if (shadow != 0) setShadowLayer(6f * d, 0f, 0f, shadow)
        }
        val is24 = DateFormat.is24HourFormat(activity)
        val hh = DateFormat.format(if (is24) "HH" else "hh", now).toString()
        val mm = DateFormat.format("mm", now).toString()
        val date = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; textAlign = Paint.Align.CENTER; typeface = Fonts.ui(activity, 500)
            textSize = 17f * d
            if (shadow != 0) setShadowLayer(4f * d, 0f, 0f, shadow)
        }
        val top = insets.top + height * 0.05f
        c.drawText(DateFormat.format("EEE, MMM d", now).toString(), width / 2f, top + date.textSize, date)
        val line = clock.textSize * 0.86f
        c.drawText(hh, width / 2f, top + date.textSize * 2f + line, clock)
        c.drawText(mm, width / 2f, top + date.textSize * 2f + line * 2f, clock)
        return out
    }

    fun destroy() {
        io.shutdownNow()
        glass.destroy()
    }

    /** A widget as Home drew it; a soft card where it couldn't be copied. */
    private class ShotView(context: android.content.Context, private val shot: Bitmap?, private val radius: Float) : View(context) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { if (shot == null) color = 0x33FFFFFF }
        override fun onDraw(canvas: Canvas) {
            if (shot != null) canvas.drawBitmap(shot, null, Rect(0, 0, width, height), paint)
            else canvas.drawRoundRect(width * 0.04f, height * 0.04f, width * 0.96f, height * 0.96f, radius, radius, paint)
        }
    }
}

/** A preview, rounded like One UI's, on a placeholder until it's drawn. */
class PreviewCard(context: Context, private val radius: Float, placeholder: Int) : View(context) {
    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = context.resources.displayMetrics.density; color = 0x1F808080
    }
    init {
        setBackgroundColor(placeholder)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        clipToOutline = true
    }
    override fun onDraw(canvas: Canvas) {
        bitmap?.let { canvas.drawBitmap(it, null, Rect(0, 0, width, height), paint) }
        val h = rim.strokeWidth / 2f
        canvas.drawRoundRect(h, h, width - h, height - h, radius, radius, rim)
    }
}
