package com.oneuiclone.home.ui

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Handler
import android.os.HandlerThread
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Launchii's live wallpaper (0.162; on by default for wallpapers set here since 0.163): the launcher's own wallpaper
 * copy ([WallpaperLayer.OWN_FILE]), drawn by this app so Home zooms the real wallpaper itself on app opens and closes.
 * The system only zooms an image wallpaper for the Pixel Launcher, and Home's copy of it (drawn in the launcher window,
 * which the system fades during the transitions) cross-dissolved with it. Blur wallpaper bakes into the picture it
 * draws ([WallpaperLayer.setHomeBitmap]). The engine runs in the launcher's process, so the zoom is passed in memory.
 */
object LiveWallpaper {
    /** The zoom the engines draw (1 = rest, as the system shows an image wallpaper). Main thread. */
    var zoom = 1f; private set
    /** Changed on the main thread; also read by Home's wallpaper loader ([isActive]). */
    private val engines = java.util.concurrent.CopyOnWriteArraySet<LiveWallpaperService.ZoomEngine>()
    /** A zoom to take once the wallpaper is out of sight ([park]). */
    private var parked: Float? = null

    /** True while Launchii's wallpaper is drawing (not a preview): Home then zooms it instead of its copy. */
    val drives get() = engines.isNotEmpty()

    fun setZoom(z: Float) {
        parked = null
        apply(z)
    }

    /**
     * Moves the wallpaper to [z] out of sight: now if it's hidden (an app covers it), else once it is. An app opened
     * from Recents then still closes with the wallpaper zooming back, without a jump while Recents shows it.
     */
    fun park(z: Float) {
        if (engines.none { it.visible }) apply(z) else parked = z
    }

    private fun apply(z: Float) {
        if (z == zoom) return
        zoom = z
        engines.forEach { it.requestDraw() }
    }

    /** The picture changed (a new wallpaper, or Blur wallpaper): any thread. */
    fun contentChanged() = engines.forEach { it.reload() }

    /** Whether wallpapers set in Launchii should go on as its live wallpaper (until the user turns it off). */
    fun wanted(context: Context) = prefs(context).getBoolean(WallpaperLayer.KEY_LIVE_WANTED, true)
    fun setWanted(context: Context, on: Boolean) { prefs(context).edit().putBoolean(WallpaperLayer.KEY_LIVE_WANTED, on).apply() }
    private fun prefs(context: Context) = context.getSharedPreferences(WallpaperLayer.PREFS, Context.MODE_PRIVATE)

    internal fun attach(e: LiveWallpaperService.ZoomEngine) { engines += e }
    internal fun detach(e: LiveWallpaperService.ZoomEngine) { engines -= e }
    internal fun visibilityChanged() {
        if (engines.none { it.visible }) parked?.let { parked = null; apply(it) }
    }

    /** True while Launchii's live wallpaper is the Home wallpaper. */
    fun isActive(context: Context): Boolean = drives || try {
        WallpaperManager.getInstance(context).wallpaperInfo?.packageName == context.packageName
    } catch (_: Exception) { false }

    /** The system's preview of Launchii's wallpaper, with its Set wallpaper button. */
    fun pickerIntent(context: Context): Intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, LiveWallpaperService::class.java))

    /** Puts the launcher's copy back as an image wallpaper, on the lock screen too if it showed Launchii's. Slow. */
    fun turnOff(context: Context): Boolean {
        val wm = WallpaperManager.getInstance(context)
        val bmp = BitmapFactory.decodeFile(java.io.File(context.filesDir, WallpaperLayer.OWN_FILE).path) ?: return false
        val lockToo = try { wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK)?.packageName == context.packageName } catch (_: Exception) { false } ||
            wm.getWallpaperId(WallpaperManager.FLAG_LOCK) <= 0
        // a blurred Home's lock screen showed the sharp original: it keeps it
        val sharp = if (lockToo && WallpaperBlur.isBlurred(context))
            BitmapFactory.decodeFile(java.io.File(context.filesDir, WallpaperBlur.SHARP_FILE).path) else null
        WallpaperBlur.keepBlur(context) {
            // straight to the system: WallpaperLayer's setters keep a live wallpaper that's on
            val both = lockToo && sharp == null
            WallpaperLayer.recordOwnCopy(context, bmp) {
                wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM or if (both) WallpaperManager.FLAG_LOCK else 0)
            }
            val lockBmp = if (both) bmp else sharp
            if (lockBmp != null) {
                if (!both) wm.setBitmap(lockBmp, null, true, WallpaperManager.FLAG_LOCK)
                WallpaperLayer.recordLockCopy(context, lockBmp)
            }
        }
        return true
    }
}

class LiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ZoomEngine()

    /**
     * Draws the copy centre-cropped to the surface at [WallpaperLayer.systemScale] × the zoom, as the system shows an
     * image wallpaper (so turning it on changes nothing at rest). Draws only when the zoom, surface or visibility
     * changes, on its own thread: a still wallpaper costs nothing.
     */
    inner class ZoomEngine : Engine() {
        private val thread = HandlerThread("LiveWallpaper").apply { start() }
        private val handler = Handler(thread.looper)
        private val lock = Any()
        private val pending = AtomicBoolean()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val matrix = Matrix()
        private val baseScale = WallpaperLayer.systemScale()
        /** Drawing thread only. [lockBitmap]: the sharp original, for a lock screen sharing a blurred Home. */
        private var bitmap: Bitmap? = null
        private var lockBitmap: Bitmap? = null
        @Volatile private var colors: WallpaperColors? = null
        /** Guarded by [lock]: false once the surface is gone, so the thread never draws into a dead one. */
        private var surfaceReady = false
        @Volatile var visible = false; private set
        @Volatile private var drawZoom = 1f
        /** Unlocked: Home's zoom and picture replace the lock screen's. */
        private val unlocked = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = requestDraw()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setOffsetNotificationsEnabled(false)
            if (!isPreview) {
                LiveWallpaper.attach(this)
                registerReceiver(unlocked, android.content.IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_NOT_EXPORTED)
            }
            drawZoom = if (isPreview) 1f else LiveWallpaper.zoom
            handler.post {
                if (!isPreview) WallpaperLayer.adoptLiveId(this@LiveWallpaperService)
                load()
            }
        }

        /** Any thread: re-reads the picture. */
        fun reload() { handler.post { load() } }

        private fun load() {
            val src = BitmapFactory.decodeFile(java.io.File(filesDir, WallpaperLayer.OWN_FILE).path) ?: return
            val lockShared = try {
                WallpaperManager.getInstance(this@LiveWallpaperService).getWallpaperId(WallpaperManager.FLAG_LOCK) <= 0
            } catch (_: Exception) { false }
            lockBitmap = if (!lockShared || !WallpaperBlur.isBlurred(this@LiveWallpaperService)) null
                else BitmapFactory.decodeFile(java.io.File(filesDir, WallpaperBlur.SHARP_FILE).path)?.let { it.copy(Bitmap.Config.HARDWARE, false) ?: it }
            val small = Bitmap.createScaledBitmap(src, max(1, src.width / 8), max(1, src.height / 8), true)
            colors = try { WallpaperColors.fromBitmap(small) } catch (_: Exception) { null }
            // a hardware bitmap stays on the GPU, so each zoom frame is a single textured draw
            bitmap = src.copy(Bitmap.Config.HARDWARE, false) ?: src
            notifyColorsChanged()
            draw()
        }

        override fun onComputeColors(): WallpaperColors? = colors

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            synchronized(lock) { surfaceReady = true }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            requestDraw()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            // the system waits for this frame before showing the wallpaper
            handler.runWithScissorsCompat { draw() }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            synchronized(lock) { surfaceReady = false }
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (!isPreview) LiveWallpaper.visibilityChanged()
            if (visible) requestDraw()
        }

        override fun onDestroy() {
            if (!isPreview) try { unregisterReceiver(unlocked) } catch (_: Exception) {}
            LiveWallpaper.detach(this)
            synchronized(lock) { surfaceReady = false }
            thread.quitSafely()
            super.onDestroy()
        }

        /** Main thread: draws the current zoom on the next turn of the drawing thread (coalesced). */
        fun requestDraw() {
            if (!isPreview) drawZoom = LiveWallpaper.zoom
            if (pending.compareAndSet(false, true)) handler.post { pending.set(false); draw() }
        }

        private fun draw() {
            val locked = isLocked()
            val b = (if (locked) lockBitmap else null) ?: bitmap ?: return
            synchronized(lock) {
                if (!surfaceReady) return
                val holder = surfaceHolder
                val frame = holder.surfaceFrame
                if (frame.isEmpty) return
                val c = try { holder.lockHardwareCanvas() } catch (_: Exception) { null } ?: return
                try {
                    val w = frame.width().toFloat(); val h = frame.height().toFloat()
                    val fit = max(w / b.width, h / b.height)
                    // the lock screen shows it at rest: Home's zoom belongs to Home
                    val z = baseScale * if (locked) 1f else drawZoom
                    matrix.setScale(fit, fit)
                    matrix.postTranslate((w - b.width * fit) / 2f, (h - b.height * fit) / 2f)
                    matrix.postScale(z, z, w / 2f, h / 2f)
                    c.drawColor(Color.BLACK)
                    c.drawBitmap(b, matrix, paint)
                } finally {
                    holder.unlockCanvasAndPost(c)
                }
            }
        }

        private fun isLocked() = try {
            getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        } catch (_: Exception) { false }

        /** Runs [block] on the drawing thread and waits for it (at most 300 ms). */
        private fun Handler.runWithScissorsCompat(block: () -> Unit) {
            if (looper.isCurrentThread) { block(); return }
            val done = java.util.concurrent.CountDownLatch(1)
            post { try { block() } finally { done.countDown() } }
            done.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }
}
