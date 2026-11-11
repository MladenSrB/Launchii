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

    private class Entry(val view: View, val dx: Float, val dy: Float) {
        lateinit var anim: ValueAnimator
        private var fraction = 0f

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
    private val pulses = ArrayList<Entry>()

    /** @param tx,ty touch point in screen coordinates. */
    fun start(views: List<View>, tx: Float, ty: Float) {
        active.forEach { it.reset() }
        active.clear()
        animateWave(views, tx, ty, pulse = false)
    }

    /** STOP / REVERSE: play the press back without changing the app return wave. */
    fun reverse() {
        for (e in active) {
            if (e.anim.isStarted) e.anim.reverse()
            else e.reset()
        }
    }

    fun pulse(views: List<View>, tx: Float, ty: Float, strength: Float = 1f) =
        animateWave(views, tx, ty, pulse = true, strength = strength)

    private fun animateWave(views: List<View>, tx: Float, ty: Float, pulse: Boolean, strength: Float = 1f) {
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
                duration = DURATION
                startDelay = (DELAY_PER_UNIT * (d / (DISTANCE_UNIT * samsungPxScale))).toLong()
                interpolator = if (pulse) Easing.LINEAR else Easing.RIPPLE
                addUpdateListener {
                    val f = it.animatedValue as Float
                    e.moveTo(if (pulse) Easing.RIPPLE.getInterpolation(minOf(f, 1f - f)) else f)
                }
                if (pulse) addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        e.moveTo(0f)
                        pulses.remove(e)
                    }
                })
            }
            if (pulse) pulses += e else active += e
            e.anim.start()
        }
    }

    fun reset() {
        active.forEach { it.reset() }
        active.clear()
        pulses.toList().forEach { it.reset() }
    }
}
