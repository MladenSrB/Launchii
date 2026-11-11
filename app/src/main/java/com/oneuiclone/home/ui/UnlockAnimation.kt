package com.oneuiclone.home.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import com.oneuiclone.home.motion.Easing
import kotlin.math.hypot

/** A short radial reveal after keyguard dismissal. All transforms are restored on interruption. */
class UnlockAnimation {
    private data class Entry(val view: View, val x: Float, val y: Float, val sx: Float, val sy: Float,
                             val alpha: Float, val dx: Float, val dy: Float, val delay: Float)
    private val entries = ArrayList<Entry>()
    private var animation: ValueAnimator? = null

    fun play(root: View, views: List<View>) {
        finish()
        if (!ValueAnimator.areAnimatorsEnabled() || root.width == 0 || root.height == 0) return
        val origin = IntArray(2)
        root.getLocationOnScreen(origin)
        val cx = origin[0] + root.width / 2f
        val cy = origin[1] + root.height * 0.55f
        val radius = hypot(root.width.toFloat(), root.height.toFloat()) / 2f
        val limit = 28f * root.resources.displayMetrics.density
        for (v in views) {
            if (!v.isShown || v.width == 0 || v.height == 0) continue
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val dx = cx - (loc[0] + v.width / 2f)
            val dy = cy - (loc[1] + v.height / 2f)
            entries += Entry(v, v.translationX, v.translationY, v.scaleX, v.scaleY, v.alpha,
                (dx * 0.08f).coerceIn(-limit, limit), (dy * 0.08f).coerceIn(-limit, limit),
                (hypot(dx, dy) / radius).coerceIn(0f, 1f) * 90f)
        }
        if (entries.isEmpty()) return
        fun apply(ms: Float) {
            for (e in entries) {
                val f = Easing.STANDARD.getInterpolation(((ms - e.delay) / 420f).coerceIn(0f, 1f))
                e.view.translationX = e.x + e.dx * (1f - f)
                e.view.translationY = e.y + e.dy * (1f - f)
                e.view.scaleX = e.sx * (0.86f + 0.14f * f)
                e.view.scaleY = e.sy * (0.86f + 0.14f * f)
                e.view.alpha = e.alpha * f
            }
        }
        apply(0f)
        animation = ValueAnimator.ofFloat(0f, 510f).apply {
            duration = 510L
            interpolator = Easing.LINEAR
            addUpdateListener { apply(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(anim: Animator) { if (animation === anim) finish() }
            })
            start()
        }
    }

    fun finish() {
        val old = animation
        animation = null
        old?.removeAllListeners()
        old?.cancel()
        for (e in entries) {
            e.view.translationX = e.x; e.view.translationY = e.y
            e.view.scaleX = e.sx; e.view.scaleY = e.sy; e.view.alpha = e.alpha
        }
        entries.clear()
    }
}
