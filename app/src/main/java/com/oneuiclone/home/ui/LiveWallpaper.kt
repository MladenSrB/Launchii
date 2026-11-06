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

    /**
     * The Aura open (0.177), drawn over the picture from the tap until the app covers the wallpaper: the picture
     * blurs and darkens, and a glow in the tapped icon's colours rises from it to the middle of the screen, then
     * breathes. Android fades Home's window during an open but never the wallpaper's, so it lasts through the
     * system's part of the open. [icon]: the tapped icon (any config); [fromX]/[fromY]/[fromSize]: its centre and
     * width on screen; [startAt]: the tap (uptime).
     */
    class Aura(val icon: Bitmap, val fromX: Float, val fromY: Float, val fromSize: Float, val startAt: Long) {
        /** Uptime its fade-out began, or 0 while it's up. */
        @Volatile var endAt = 0L
    }

    /** The Aura being drawn, or null. */
    @Volatile var aura: Aura? = null; internal set

    /** Main thread: starts [a] on the wallpaper (replacing any other). */
    fun startAura(a: Aura) {
        aura = a
        engines.forEach { it.animateAura() }
    }

    /** Fades the Aura out (Home came back before an app covered it, or the launch failed). */
    fun endAura() {
        val a = aura ?: return
        if (a.endAt == 0L) a.endAt = android.os.SystemClock.uptimeMillis()
        engines.forEach { it.animateAura() }
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
        if (engines.none { it.visible }) {
            // the app covers the wallpaper: the Aura is over, and the close finds the plain picture
            // (redrawn now, out of sight: a visible wallpaper first shows the last frame it posted)
            aura?.let {
                aura = null
                com.oneuiclone.home.debug.AnimLog.log("aura: wallpaper hidden, dropped")
                engines.forEach { e -> e.requestDraw() }
            }
            parked?.let { parked = null; apply(it) }
        }
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

/** Aura: the glow and blur come in over [AURA_MS] (Material emphasized decelerate), as in the 2026-11-06 mockup. */
private const val AURA_MS = 420f
private val AURA_CURVE = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
/** Fade back to the plain picture when Home returns uncovered, and the longest an uncovered Aura stays up. */
private const val AURA_FADE_MS = 250L
private const val AURA_MAX_MS = 8000L
/** The picture's blur (sigma, as a fraction of the screen width) and darkening at full strength. */
private const val AURA_BLUR = 10f / 300f
private const val AURA_DIM = 0.5f
/** The glow's final width (fraction of the screen width), height of its centre (fraction of the screen) and opacity. */
private const val AURA_GLOW = 440f / 300f
private const val AURA_GLOW_Y = 0.47f
private const val AURA_GLOW_ALPHA = 0.95f
/** Once up, the glow breathes ±5 % every 1.1 s while the app is still starting. */
private const val AURA_BREATH = 0.05f
private const val AURA_BREATH_MS = 1100f
private const val GLOW_SAMPLES = 12
private const val GLOW_PX = 96
private const val GLOW_SATURATION = 1.3f
/** From this blur (px), the picture is blurred at 1/[BLUR_DOWNSCALE] of the screen's size. */
private const val BLUR_SMALL_FROM = 8f
private const val BLUR_DOWNSCALE = 4f
/** Builds Aura glows off the drawing thread, so the Aura starts on its first frame. */
private val glowWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "AuraGlow") }

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
            handler.post { choreographer?.removeFrameCallback(auraFrame) }
            thread.quitSafely()
            super.onDestroy()
        }

        /** Main thread: draws the current zoom on the next turn of the drawing thread (coalesced). */
        fun requestDraw() {
            if (!isPreview) drawZoom = LiveWallpaper.zoom
            // the Aura's frame loop draws every frame, with this zoom
            if (auraDrawing != null && LiveWallpaper.aura != null) return
            if (pending.compareAndSet(false, true)) handler.post { pending.set(false); draw() }
        }

        private fun draw() {
            val locked = isLocked()
            val b = (if (locked) lockBitmap else null) ?: bitmap ?: return
            val aura = if (locked || isPreview) null else LiveWallpaper.aura
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
                    if (aura == null) c.drawBitmap(b, matrix, paint) else drawAura(c, b, aura, w, h)
                } finally {
                    holder.unlockCanvasAndPost(c)
                }
            }
        }

        // ---- Aura (drawing thread, except [auraDrawing]) ----
        private val auraNode = android.graphics.RenderNode("aura")
        private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val glowRect = android.graphics.RectF()
        /** Built on [glowWorker] for [glowFor]; drawn once it's there. */
        @Volatile private var glow: Bitmap? = null
        @Volatile private var glowFor: LiveWallpaper.Aura? = null
        private var choreographer: android.view.Choreographer? = null
        /** The Aura the frame loop is running for (read on the main thread too). */
        @Volatile private var auraDrawing: LiveWallpaper.Aura? = null
        private var auraFrames = 0
        private var auraLast = 0L
        private var auraGap = 0L
        private var auraSlowest = 0L
        private val auraFrame = android.view.Choreographer.FrameCallback { stepAura() }

        /** Any thread: runs the Aura's frame loop until it ends or the wallpaper hides; its glow joins once built. */
        fun animateAura() {
            if (isPreview) return
            handler.post {
                val a = LiveWallpaper.aura ?: return@post
                if (glowFor !== a) {
                    glowFor = a
                    glow = null
                    glowWorker.execute {
                        val t0 = System.nanoTime()
                        val g = try { buildGlow(a.icon) } catch (e: Exception) { null }
                        if (glowFor === a) glow = g
                        com.oneuiclone.home.debug.AnimLog.log("aura: glow %s in %.1fms".format(if (g == null) "failed" else "built", (System.nanoTime() - t0) / 1e6))
                    }
                }
                if (auraDrawing !== a) { auraDrawing = a; auraFrames = 0; auraLast = 0L; auraGap = 0L; auraSlowest = 0L }
                val ch = choreographer ?: android.view.Choreographer.getInstance().also { choreographer = it }
                ch.removeFrameCallback(auraFrame)
                ch.postFrameCallback(auraFrame)
            }
        }

        private fun stepAura() {
            val a = auraDrawing
            val now = android.os.SystemClock.uptimeMillis()
            if (a != null && LiveWallpaper.aura === a) {
                // never covered (an app that shows the wallpaper): let it go
                if (a.endAt == 0L && now - a.startAt > AURA_MAX_MS) a.endAt = now
                if (a.endAt != 0L && now - a.endAt >= AURA_FADE_MS) LiveWallpaper.aura = null
            }
            if (auraLast != 0L) auraGap = max(auraGap, now - auraLast)
            auraLast = now
            auraFrames++
            draw()
            auraSlowest = max(auraSlowest, android.os.SystemClock.uptimeMillis() - now)
            if (a != null && LiveWallpaper.aura === a) { choreographer?.postFrameCallback(auraFrame); return }
            if (a != null) com.oneuiclone.home.debug.AnimLog.log("aura: $auraFrames frames over ${now - a.startAt}ms, longest gap ${auraGap}ms, slowest draw ${auraSlowest}ms" +
                if (a.endAt != 0L) ", faded out" else "")
            auraDrawing = null
            glow = null; glowFor = null
        }

        /**
         * The picture blurred and darkened by the Aura's strength, then the glow rising from the icon to the middle
         * of the screen. A fade-out ([LiveWallpaper.Aura.endAt]) takes all of it back to the plain picture.
         */
        private fun drawAura(c: android.graphics.Canvas, b: Bitmap, a: LiveWallpaper.Aura, w: Float, h: Float) {
            val now = android.os.SystemClock.uptimeMillis()
            val m = (now - a.startAt).toFloat()
            val p = AURA_CURVE.getInterpolation((m / AURA_MS).coerceIn(0f, 1f))
            val out = if (a.endAt == 0L) 1f else 1f - ((now - a.endAt) / AURA_FADE_MS.toFloat()).coerceIn(0f, 1f)
            val k = p * out
            val sigma = AURA_BLUR * w * k
            if (sigma < 0.5f) c.drawBitmap(b, matrix, paint)
            else {
                // blurred at a quarter of the size (a blur hides it), then drawn back up to the screen
                val d = if (sigma >= BLUR_SMALL_FROM) BLUR_DOWNSCALE else 1f
                auraNode.setPosition(0, 0, kotlin.math.ceil(w / d).toInt(), kotlin.math.ceil(h / d).toInt())
                auraNode.pivotX = 0f; auraNode.pivotY = 0f
                auraNode.scaleX = d; auraNode.scaleY = d
                val rc = auraNode.beginRecording()
                rc.scale(1f / d, 1f / d)
                rc.drawBitmap(b, matrix, paint)
                auraNode.endRecording()
                auraNode.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(sigma / d, sigma / d, android.graphics.Shader.TileMode.CLAMP))
                c.drawRenderNode(auraNode)
            }
            c.drawColor(Color.argb(AURA_DIM * k, 0f, 0f, 0f))
            val g = glow ?: return
            val breath = if (m > AURA_MS) 1f + AURA_BREATH * kotlin.math.sin((m - AURA_MS) / AURA_BREATH_MS * 2f * Math.PI.toFloat()) else 1f
            val size = (a.fromSize + (AURA_GLOW * w - a.fromSize) * p) * breath
            val cx = a.fromX + (w / 2f - a.fromX) * p
            val cy = a.fromY + (h * AURA_GLOW_Y - a.fromY) * p
            glowPaint.alpha = (255f * (p * 1.4f).coerceAtMost(1f) * AURA_GLOW_ALPHA * out).toInt()
            glowRect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
            c.drawBitmap(g, null, glowRect, glowPaint)
        }

        /**
         * A soft round glow in [icon]'s colours, laid out as in the icon (the icon fills its middle half): each
         * pixel is a Gaussian blend of a 12 × 12 sampling of the icon, weighted toward its colourful pixels, so a
         * white plate with a red M glows red rather than white. A grey icon glows in its lightest parts. Runs on
         * [glowWorker] (separable, about 60 k multiply-adds).
         */
        private fun buildGlow(icon: Bitmap): Bitmap {
            val soft = if (icon.config == Bitmap.Config.HARDWARE) icon.copy(Bitmap.Config.ARGB_8888, false) else icon
            val n = GLOW_SAMPLES
            val px = IntArray(n * n)
            Bitmap.createScaledBitmap(soft, n, n, true).getPixels(px, 0, n, 0, 0, n, n)
            val cw = FloatArray(n * n); val cr = FloatArray(n * n); val cg = FloatArray(n * n); val cb = FloatArray(n * n)
            for (i in px.indices) {
                val col = px[i]
                val al = Color.alpha(col) / 255f
                if (al < 0.1f) continue
                val r = Color.red(col) / 255f; val g = Color.green(col) / 255f; val bl = Color.blue(col) / 255f
                val chroma = maxOf(r, g, bl) - minOf(r, g, bl)
                val lum = 0.2126f * r + 0.7152f * g + 0.0722f * bl
                val wt = al * (0.03f + 6f * chroma + 0.3f * lum)
                cw[i] = wt; cr[i] = wt * r; cg[i] = wt * g; cb[i] = wt * bl
            }
            val s = GLOW_PX
            // separable Gaussian: weight of sample j (at 0.25 + 0.5 * (j + 0.5) / n across the glow) at pixel x
            val sigma2 = 2f * 0.12f * 0.12f
            val gauss = FloatArray(s * n) { idx ->
                val u = (idx / n + 0.5f) / s
                val pj = 0.25f + 0.5f * ((idx % n) + 0.5f) / n
                kotlin.math.exp(-(u - pj) * (u - pj) / sigma2)
            }
            // down the columns first (per glow row y, per sample column), then across
            val tw = FloatArray(s * n); val tr = FloatArray(s * n); val tg = FloatArray(s * n); val tb = FloatArray(s * n)
            for (y in 0 until s) for (col in 0 until n) {
                var w = 0f; var r = 0f; var g = 0f; var bl = 0f
                for (row in 0 until n) {
                    val gy = gauss[y * n + row]; val i = row * n + col
                    w += gy * cw[i]; r += gy * cr[i]; g += gy * cg[i]; bl += gy * cb[i]
                }
                val t = y * n + col
                tw[t] = w; tr[t] = r; tg[t] = g; tb[t] = bl
            }
            val out = IntArray(s * s)
            for (y in 0 until s) for (x in 0 until s) {
                val d = kotlin.math.hypot((x + 0.5f) / s - 0.5f, (y + 0.5f) / s - 0.5f) / 0.5f
                if (d >= 1f) continue
                var r = 0f; var g = 0f; var bl = 0f; var wsum = 0f
                for (col in 0 until n) {
                    val gx = gauss[x * n + col]; val t = y * n + col
                    wsum += gx * tw[t]; r += gx * tr[t]; g += gx * tg[t]; bl += gx * tb[t]
                }
                if (wsum <= 1e-12f) continue
                // brighter toward the middle, fading to nothing at the rim
                val e = d * d * (3f - 2f * d)
                val alpha = Math.pow((1f - e).toDouble(), 1.3).toFloat()
                val lift = 0.15f * (1f - d) * (1f - d)
                r /= wsum; g /= wsum; bl /= wsum
                // a little more colour than the blend (averaging greys it), then lighter toward the middle
                val grey = 0.2126f * r + 0.7152f * g + 0.0722f * bl
                fun ch(v: Float) = (grey + (v - grey) * GLOW_SATURATION).let { it + (1f - it) * lift }.coerceIn(0f, 1f)
                out[y * s + x] = Color.argb(alpha, ch(r), ch(g), ch(bl))
            }
            val bmp = Bitmap.createBitmap(out, s, s, Bitmap.Config.ARGB_8888)
            return bmp.copy(Bitmap.Config.HARDWARE, false) ?: bmp
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
