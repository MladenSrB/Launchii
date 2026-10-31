package com.oneuiclone.home.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.core.graphics.PathParser
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.icons.IconFactory
import com.oneuiclone.home.motion.Easing
import com.oneuiclone.home.widgets.BubblesWidget
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Bubbles widget on Home, an icon among icons: its apps' Home icons cascading back from the first
 * ([BubblesWidget.cascade]), drawn live rather than as one bitmap so that with Frosted icons each one has the blurred
 * wallpaper behind its see-through plate, in its own place, as every Frosted icon does ([plateGlass]). With no apps
 * chosen it shows [icon] (BubblesWidget.emptyTile).
 *
 * While its bubbles open ([startOpening], then [appDone] per app as the system answers): the label reads Opening…, a
 * white ring in the icon shape fills around the stack with each app done, and each app hops with a little bubble pop
 * and a haptic tick (the mockup's option B).
 */
class BubblesIconView(context: Context, dp: DeviceProfile) : IconView(context, dp) {

    var layers: List<BubblesWidget.Layer> = emptyList()
        set(v) { field = v; invalidate() }

    private val glassPaint = GlassPaint()
    private val toWindow = Matrix()
    private val aim = Matrix()
    private var mask: Path? = null
    private var maskSize = -1

    override fun drawIcon(canvas: Canvas, left: Int, top: Int) {
        if (layers.isEmpty()) return super.drawIcon(canvas, left, top)
        val s = iconSize.toFloat()
        val g = plateGlass
        if (g != null) {
            toWindow.reset(); transformMatrixToGlobal(toWindow); toWindow.preTranslate(left.toFloat(), top.toFloat())
            if (maskSize != iconSize) { mask = IconFactory.maskPath(s); maskSize = iconSize }
        }
        val now = SystemClock.uptimeMillis()
        canvas.save()
        canvas.translate(left.toFloat(), top.toFloat())
        BubblesWidget.draw(canvas, layers, s, if (g == null) null else { r ->
            val k = r.width() / s
            aim.set(toWindow); aim.preTranslate(r.left, r.top); aim.preScale(k, k)
            if (glassPaint.aim(g, aim)) {
                canvas.save()
                canvas.translate(r.left, r.top); canvas.scale(k, k)
                canvas.drawPath(mask!!, glassPaint.paint)
                canvas.restore()
            }
        }, if (opening == null) null else { i -> hop(i, now) })
        opening?.let { drawFeedback(canvas, it, s, now) }
        canvas.restore()
        if (opening != null) postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------------------------------------------
    // opening feedback
    // ---------------------------------------------------------------------------------------------

    private class Opening(val count: Int, val start: Long) {
        var done = 0
        /** When each app's bubble came (or was turned down), by its place in the stack; 0 = not yet. */
        val doneAt = LongArray(count)
        var ringFrom = 0f
        var ringTo = 0f
        var ringAt = start
        var finishedAt = 0L
    }

    private var opening: Opening? = null
    private var restLabel: CharSequence = ""
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFFFFFFFF.toInt()
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val ringPath = Path()
    private val ringPart = Path()
    private var ringMeasure: PathMeasure? = null
    private var ringSize = -1
    private val timeout = Runnable { opening?.let { finish(it) } }

    /** The tap: press in, Opening…, and the ring starts, while [count] apps' bubbles are posted. */
    fun startOpening(count: Int) {
        if (layers.isEmpty() || count == 0) return
        if (opening == null) restLabel = label
        opening = Opening(count, SystemClock.uptimeMillis())
        label = "Opening…"
        animate().cancel()
        scaleX = 0.9f; scaleY = 0.9f
        animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
        removeCallbacks(timeout)
        postDelayed(timeout, TIMEOUT_MS)
        invalidate()
    }

    /** App [index] (0 = the first) has its bubble, or was turned down: it hops, and the ring fills a step. */
    fun appDone(index: Int) {
        val o = opening ?: return
        if (index !in 0 until o.count || o.doneAt[index] != 0L) return
        val now = SystemClock.uptimeMillis()
        o.doneAt[index] = now
        o.done++
        o.ringFrom = ring(o, now); o.ringTo = o.done.toFloat() / o.count; o.ringAt = now
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        if (o.done == o.count) finish(o)
        invalidate()
    }

    private fun finish(o: Opening) {
        if (o.finishedAt != 0L) return
        val now = SystemClock.uptimeMillis()
        o.ringFrom = ring(o, now); o.ringTo = 1f; o.ringAt = now
        o.finishedAt = now
        removeCallbacks(timeout)
        // the label comes back once the ring has faded
        postDelayed({
            if (opening === o) {
                opening = null
                label = restLabel
                animate().scaleX(1f).scaleY(1f).setDuration(1).start()
                invalidate()
            }
        }, FINISH_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(timeout)
        super.onDetachedFromWindow()
    }

    private fun ring(o: Opening, now: Long): Float {
        val t = ((now - o.ringAt) / RING_STEP_MS.toFloat()).coerceIn(0f, 1f)
        val stepped = o.ringFrom + (o.ringTo - o.ringFrom) * Easing.CUBIC_OUT.getInterpolation(t)
        if (o.finishedAt != 0L) return stepped
        // the system can take a while to answer: the ring keeps creeping along the posting schedule meanwhile, short of
        // the next app's step, so it never looks stuck and still jumps when an app is done
        val creep = minOf((now - o.start) / (o.count * POST_MS + CREEP_SLACK_MS), (o.done + 0.85f) / o.count)
        return maxOf(stepped, creep)
    }

    /** Layer [i]'s hop now: up and back over [HOP_MS] from when its app was done. */
    private fun hop(i: Int, now: Long): Float {
        val at = opening?.doneAt?.getOrNull(i) ?: return 0f
        if (at == 0L) return 0f
        val t = (now - at) / HOP_MS.toFloat()
        return if (t <= 0f || t >= 1f) 0f else sin(PI * t).toFloat()
    }

    private fun drawFeedback(c: Canvas, o: Opening, s: Float, now: Long) {
        // the ring, around the icon's whole square, in its shape, from the top clockwise
        if (ringSize != iconSize) {
            ringSize = iconSize
            val pad = s * RING_PAD
            ringPath.reset()
            PathParser.createPathFromPathData(RING_PATH).transform(Matrix().apply {
                setScale((s + 2 * pad) / 100f, (s + 2 * pad) / 100f); postTranslate(-pad, -pad)
            }, ringPath)
            ringMeasure = PathMeasure(ringPath, true)
            ringPaint.strokeWidth = s * 0.03f
            ringPaint.setShadowLayer(s * 0.03f, 0f, 0f, 0xCCFFFFFF.toInt())
        }
        val fade = if (o.finishedAt == 0L) 1f else 1f - ((now - o.finishedAt - RING_STEP_MS) / FADE_MS.toFloat()).coerceIn(0f, 1f)
        val appear = ((now - o.start) / 150f).coerceIn(0f, 1f)
        val m = ringMeasure ?: return
        val p = ring(o, now)
        if (p > 0f && fade > 0f) {
            ringPart.reset()
            m.getSegment(0f, m.length * p, ringPart, true)
            ringPaint.alpha = (255 * fade * appear).toInt()
            c.drawPath(ringPart, ringPaint)
        }
        // a little bubble pop from each app as it's done: six dots flying out from its middle and fading
        val rects = BubblesWidget.cascade(layers.size, s)
        for (i in 0 until minOf(o.count, rects.size)) {
            val at = o.doneAt[i]
            if (at == 0L) continue
            val t = (now - at - 60) / POP_MS.toFloat()
            if (t <= 0f || t >= 1f) continue
            val r = rects[i]
            val reach = r.width() * 0.62f * Easing.CUBIC_OUT.getInterpolation(t)
            dotPaint.alpha = (255 * (if (t < 0.3f) t / 0.3f else 1f - (t - 0.3f) / 0.7f)).toInt()
            val radius = s * 0.055f * (1f - 0.7f * t)
            for (q in 0 until 6) {
                val a = q / 6.0 * 2 * PI + i
                c.drawCircle(r.centerX() + (cos(a) * reach).toFloat(), r.centerY() + (sin(a) * reach).toFloat(), radius, dotPaint)
            }
        }
    }

    private companion object {
        /** The One UI icon shape (IconFactory.ONE_UI_MASK) redrawn to start at the top and run clockwise. */
        const val RING_PATH = "M50,1C77.063,1,99,6.935,99,50C99,93.063,77.063,99,50,99C22.935,99,1,93.063,1,50C1,6.935,22.935,1,50,1z"
        const val RING_PAD = 0.07f
        /** BubblesWidget's spacing between posts, and the system's usual time to answer the last, for the ring's creep. */
        const val POST_MS = 300f
        const val CREEP_SLACK_MS = 900f
        const val RING_STEP_MS = 260L
        const val FADE_MS = 250L
        const val HOP_MS = 300L
        const val POP_MS = 420L
        const val FINISH_MS = 700L
        /** If the system never answers for some app, the feedback ends anyway. */
        const val TIMEOUT_MS = 5000L
    }
}
