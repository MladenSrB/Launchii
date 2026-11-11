package com.oneuiclone.home.ui

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.View
import com.oneuiclone.home.motion.Easing

/** A separate render layer keeps launch blur independent of search and drawer effects. */
class AppTransitionBlur(private val onRadiusChanged: (Float) -> Unit, private vararg val layers: View) {
    companion object {
        const val MAX_RADIUS_DP = 28f
        const val CLEAR_DURATION_MS = 420L
    }
    private var progress = 0f
    private var animation: ValueAnimator? = null
    private val maxRadius = if (layers.first().context.getSystemService(ActivityManager::class.java).isLowRamDevice)
        0f else MAX_RADIUS_DP * layers.first().resources.displayMetrics.density

    fun animateTo(target: Float, duration: Long) {
        animation?.cancel()
        if (maxRadius == 0f || !ValueAnimator.areAnimatorsEnabled()) {
            setProgress(0f)
            return
        }
        val end = target.coerceIn(0f, 1f)
        val clearing = end < progress
        animation = ValueAnimator.ofFloat(progress, end).apply {
            this.duration = if (clearing) CLEAR_DURATION_MS else duration
            interpolator = if (clearing) Easing.APP_BLUR_OUT else Easing.APP_BLUR_IN
            addUpdateListener { apply(it.animatedValue as Float) }
            start()
        }
    }

    fun setProgress(value: Float) {
        animation?.cancel()
        animation = null
        apply(if (maxRadius == 0f || !ValueAnimator.areAnimatorsEnabled()) 0f else value.coerceIn(0f, 1f))
    }

    private fun apply(value: Float) {
        progress = value
        val radius = maxRadius * value
        val effect = if (radius < 0.1f) null else
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        layers.forEach { it.setRenderEffect(effect) }
        onRadiusChanged(radius)
    }
}
