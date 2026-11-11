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
        private const val RETURN_REBOUND_MS = 480L
        private val RETURN_REBOUND = Easing.spring(220f, 0.72f, RETURN_REBOUND_MS)
    }

    private class Entry(val view: View, val dx: Float, val dy: Float) {
        lateinit var anim: ValueAnimator
        var fraction = 0f
            private set

        fun moveTo(next: Float) {
            val change = next - fraction
            view.translationX += dx * change
            view.translationY += dy * change
            fraction = next
        }

        fun reset() {
            anim.cancel()
            moveTo(0f)
        }
    }

    private val active = ArrayList<Entry>()
    private val returning = ArrayList<Entry>()
    private var returnReleasing = false

    /** @param tx,ty touch point in screen coordinates. */
    fun start(views: List<View>, tx: Float, ty: Float) {
        active.forEach { it.reset() }
        active.clear()
        animateWave(views, tx, ty)
    }

    /** STOP / REVERSE: play the press back without changing the app return wave. */
    fun reverse() {
        for (e in active) {
            if (e.anim.isStarted) e.anim.reverse()
            else e.reset()
        }
    }

    /** Pull inward once, then hold until the landing tail releases the neighbours. */
    fun beginReturn(views: List<View>, tx: Float, ty: Float, strength: Float = 1f) {
        cancelReturn()
        if (!ValueAnimator.areAnimatorsEnabled()) return
        animateWave(views, tx, ty, returnWave = true, strength = strength)
    }

    /** Rebound from the current inward pose, including an interrupted approach. */
    fun finishReturn() {
        if (returnReleasing || returning.isEmpty()) return
        returnReleasing = true
        for (e in returning.toList()) {
            e.anim.cancel()
            e.anim = ValueAnimator.ofFloat(e.fraction, 0f).apply {
                duration = RETURN_REBOUND_MS
                interpolator = RETURN_REBOUND
                addUpdateListener { e.moveTo(it.animatedValue as Float) }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        e.moveTo(0f)
                        returning.remove(e)
                    }
                })
            }
            e.anim.start()
        }
    }

    /** Remove only the return wave's displacement; press/drag translations remain intact. */
    fun cancelReturn() {
        for (e in returning.toList()) {
            e.anim.removeAllListeners()
            e.reset()
        }
        returning.clear()
        returnReleasing = false
    }

    private fun animateWave(views: List<View>, tx: Float, ty: Float, returnWave: Boolean = false, strength: Float = 1f) {
        val loc = IntArray(2)
        for (v in views) {
            if (!v.isShown) continue
            v.getLocationOnScreen(loc)
            val cx = loc[0] + v.width * v.scaleX / 2f - v.translationX
            val cy = loc[1] + v.height * v.scaleY / 2f - v.translationY
            val d = hypot(cx - tx, cy - ty)
            if (d < 1f) continue
            val e = Entry(v, (tx - cx) / DISTANCE_UNIT * strength, (ty - cy) / DISTANCE_UNIT * strength)
            e.anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (returnWave) 200L else DURATION
                startDelay = (DELAY_PER_UNIT * (d / (DISTANCE_UNIT * samsungPxScale))).toLong()
                interpolator = if (returnWave) Easing.STANDARD else Easing.RIPPLE
                addUpdateListener { e.moveTo(it.animatedValue as Float) }
            }
            if (returnWave) returning += e else active += e
            e.anim.start()
        }
    }

    fun reset() {
        active.forEach { it.reset() }
        active.clear()
        cancelReturn()
    }
}
