package com.oneuiclone.home.ui

import android.animation.ValueAnimator
import android.graphics.PointF
import android.view.View
import android.view.animation.Interpolator

/** App motion on a layer outside the drawer and folder transforms. */
internal class AppContentMotion(private val view: View) {
    private data class Transform(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
        fun then(outer: Transform) = Transform(scale * outer.scale, x * outer.scale + outer.x, y * outer.scale + outer.y)

        fun interpolate(to: Transform, fraction: Float) = Transform(
            scale + (to.scale - scale) * fraction,
            x + (to.x - x) * fraction,
            y + (to.y - y) * fraction,
        )
    }

    private val identity = Transform()
    private var opening = identity
    private var returning = identity
    private var openAnimation: ValueAnimator? = null
    private var returnAnimation: ValueAnimator? = null
    private var openPivot: PointF? = null

    private fun centeredScale(scale: Float, pivot: PointF?) = Transform(
        scale, (pivot?.x ?: view.width / 2f) * (1f - scale), (pivot?.y ?: view.height / 2f) * (1f - scale),
    )

    private fun apply() {
        val transform = opening.then(returning)
        view.pivotX = 0f
        view.pivotY = 0f
        view.scaleX = transform.scale
        view.scaleY = transform.scale
        view.translationX = transform.x
        view.translationY = transform.y
    }

    fun open(scale: Float, duration: Long, curve: Interpolator, pivot: PointF? = null) {
        openAnimation?.cancel()
        if (pivot != null || opening == identity) openPivot = pivot
        openAnimation = animate(opening, centeredScale(scale, openPivot), duration, curve) {
            opening = it
            apply()
        }
    }

    fun cancelOpening(duration: Long, curve: Interpolator) = open(1f, duration, curve)

    /** The current pose becomes the starting point when the app itself reverses direction. */
    fun returnToRest(duration: Long, curve: Interpolator, onComplete: () -> Unit = {}) {
        openAnimation?.cancel()
        returnAnimation?.cancel()
        returning = opening.then(returning)
        opening = identity
        openPivot = null
        returnAnimation = animate(returning, identity, duration, curve, onComplete) {
            returning = it
            apply()
        }
    }

    fun park(scale: Float, pivot: PointF?) {
        openAnimation?.cancel()
        returnAnimation?.cancel()
        opening = identity
        openPivot = null
        returning = centeredScale(scale, pivot)
        apply()
    }

    private fun animate(from: Transform, to: Transform, duration: Long, curve: Interpolator,
                        onComplete: () -> Unit = {}, update: (Transform) -> Unit): ValueAnimator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = curve
            addUpdateListener { update(from.interpolate(to, it.animatedValue as Float)) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) onComplete()
                }
            })
            start()
        }
}
