package com.oneuiclone.home.ui

import android.animation.ValueAnimator
import android.view.View
import com.oneuiclone.home.motion.Easing
import kotlin.math.hypot

/**
 * One UI 8.5 touch ripple (ViewRippleAnimationHelper / RippleAnimationParams, spec §11):
 * every sibling moves toward the touch point by d/50, starting 6 ms·(d/unit) later, over 650 ms with
 * the overshooting (0.807, 0.586, 0.266, 1.628) curve. STOP/REVERSE plays the same animator backwards.
 */
class TouchRipple(private val samsungPxScale: Float) {

    companion object {
        const val DURATION = 650L
        const val DISTANCE_UNIT = 50f
        const val DELAY_PER_UNIT = 6L
    }

    private class Entry(val view: View, val anim: ValueAnimator, val baseX: Float, val baseY: Float)

    private val active = ArrayList<Entry>()

    /** @param tx,ty touch point in screen coordinates. */
    fun start(views: List<View>, tx: Float, ty: Float) {
        reset()
        val loc = IntArray(2)
        for (v in views) {
            if (!v.isShown) continue
            v.getLocationOnScreen(loc)
            val cx = loc[0] + v.width * v.scaleX / 2f - v.translationX
            val cy = loc[1] + v.height * v.scaleY / 2f - v.translationY
            val d = hypot(cx - tx, cy - ty)
            if (d < 1f) continue
            val ux = (cx - tx) / d
            val uy = (cy - ty) / d
            val units = d / DISTANCE_UNIT                 // our px; displacement = d / 50 (density independent ratio)
            val dx = -units * ux
            val dy = -units * uy
            val baseX = v.translationX
            val baseY = v.translationY
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = DURATION
                interpolator = Easing.RIPPLE
                startDelay = (DELAY_PER_UNIT * (d / (DISTANCE_UNIT * samsungPxScale))).toLong()
                addUpdateListener {
                    val f = it.animatedValue as Float
                    v.translationX = baseX + dx * f
                    v.translationY = baseY + dy * f
                }
            }
            active += Entry(v, anim, baseX, baseY)
            anim.start()
        }
    }

    /** STOP / REVERSE: play back from wherever each item currently is. */
    fun reverse() {
        for (e in active) {
            if (e.anim.isStarted && e.anim.isRunning) {
                e.anim.reverse()
            } else if (e.anim.isStarted) {
                e.anim.reverse()
            } else {
                e.anim.cancel()
                e.view.translationX = e.baseX
                e.view.translationY = e.baseY
            }
        }
    }

    /** One-shot "release" wave from a point (app returning to its icon): out and back. */
    fun pulse(views: List<View>, tx: Float, ty: Float) {
        start(views, tx, ty)
        val first = active.firstOrNull()?.view ?: return
        first.postDelayed({ reverse() }, DURATION / 2)
    }

    fun reset() {
        for (e in active) {
            e.anim.cancel()
            e.view.translationX = e.baseX
            e.view.translationY = e.baseY
        }
        active.clear()
    }
}
