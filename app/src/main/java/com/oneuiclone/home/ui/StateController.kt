package com.oneuiclone.home.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.View
import com.oneuiclone.home.motion.Easing
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * HOME ⇄ APPS screen transition, ported from One UI Home (spec §3):
 *  - the screen being shown:  scale 0.94→1, alpha = ENTER(f), translationY ±86dp → 0
 *  - the screen being hidden: scale 1→0.94, alpha = 1−EXIT(f), translationY 0 → ∓86dp·f
 *  - release: fling if |v| > 1 px/ms, commit if fling or f > 0.4, reverse fling cancels,
 *    duration = max(100, 1200 / max(2, |v|·0.5) · max(0.2, remaining)), cubic / quintic (|v| > 10).
 *
 * [liftHome] (search from Home, "Lift-Off"): Home instead grows toward the viewer, blurs and fades, and the Apps
 * screen's blurred background comes in under it; the search view drives it frame by frame and [settle]s it.
 */
class StateController(
    private val home: View,
    private val apps: View,
    private val background: BackgroundController,
    private val transitionY: () -> Float,
) {
    enum class State { HOME, APPS }

    var state = State.HOME; private set
    var onStateChanged: ((State) -> Unit)? = null
    var onTransitionStart: ((toApps: Boolean) -> Unit)? = null

    private var anim: ValueAnimator? = null
    /** Progress of the current transition toward [target]. */
    private var f = 0f
    private var toApps = true
    val isTransitioning get() = anim?.isRunning == true || dragging
    private var dragging = false

    /** The next transition is Lift-Off (set before [begin]; cleared when it settles). */
    var liftHome = false
    /** No blur on low-RAM (Go) phones: Home only grows and fades there. */
    private val liftBlur = if (home.context.getSystemService(android.app.ActivityManager::class.java).isLowRamDevice) 0f
        else home.resources.displayMetrics.density * LIFT_BLUR_DP

    fun begin(towardApps: Boolean) {
        anim?.cancel()
        toApps = towardApps
        dragging = true
        apps.visibility = View.VISIBLE
        home.visibility = View.VISIBLE
        // composite cached layers while scaling/fading (no per-frame redraw of every icon)
        home.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        apps.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        onTransitionStart?.invoke(towardApps)
    }

    /** Raw finger progress (no interpolation while dragging, like gotoScreenWithAnimation(ongoing=false)). */
    fun drag(progress: Float) {
        apply(progress.coerceIn(0f, 1f))
    }

    /**
     * @param velocity Samsung-px/ms, positive toward the target screen.
     */
    fun release(velocity: Float) {
        dragging = false
        val isFling = abs(velocity) > 1f
        val reverseFling = isFling && velocity < 0f
        val commit = (isFling || f > 0.4f) && !reverseFling
        val remaining = if (commit) 1f - f else f
        val duration = max(100L, ((1200f / max(2f, abs(velocity) * 0.5f)) * max(0.2f, remaining)).toLong())
        val interp = if (abs(velocity) > 10f) Easing.QUINT_OUT else Easing.CUBIC_OUT
        animateTo(if (commit) 1f else 0f, duration, interp)
    }

    fun goTo(target: State, duration: Long = if (target == State.APPS) 200L else 450L) {
        if (target == state && anim?.isRunning != true && !dragging) return
        if (anim?.isRunning == true && (target == State.APPS) == toApps) return
        if (anim?.isRunning == true) {
            // turned around mid-way: run the same transition back from where it is (no restart, no blink)
            animateTo(0f, max(100L, (duration * f).toLong()), Easing.CUBIC_OUT)
            return
        }
        begin(target == State.APPS)
        dragging = false
        f = 0f
        animateTo(1f, duration, Easing.CUBIC_OUT)
    }

    /** Ends a driven transition where it is: [committed] = reached the screen it was heading for. */
    fun settle(committed: Boolean) {
        anim?.cancel()
        dragging = false
        apply(if (committed) 1f else 0f)
        finish(committed)
    }

    fun setImmediate(target: State) {
        anim?.cancel()
        toApps = target == State.APPS
        apply(1f)
        finish(true)
    }

    private fun animateTo(end: Float, duration: Long, interp: android.view.animation.Interpolator) {
        anim?.cancel()
        val start = f
        anim = ValueAnimator.ofFloat(start, end).apply {
            this.duration = duration
            interpolator = interp
            addUpdateListener { apply(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) finish(end >= 1f) }
            })
            start()
        }
    }

    private fun finish(committed: Boolean) {
        val newState = if (committed) (if (toApps) State.APPS else State.HOME) else (if (toApps) State.HOME else State.APPS)
        val showApps = newState == State.APPS
        home.setLayerType(View.LAYER_TYPE_NONE, null)
        apps.setLayerType(View.LAYER_TYPE_NONE, null)
        home.setRenderEffect(null)
        liftHome = false
        // settle exactly
        setScreen(apps, showApps)
        setScreen(home, !showApps)
        background.set(BackgroundController.Kind.APPS, if (showApps) 1f else 0f)
        f = 0f
        if (newState != state) {
            state = newState
        }
        onStateChanged?.invoke(state)
    }

    private fun setScreen(v: View, shown: Boolean) {
        v.scaleX = 1f; v.scaleY = 1f; v.translationY = 0f
        v.alpha = if (shown) 1f else 0f
        v.visibility = if (shown) View.VISIBLE else View.INVISIBLE
    }

    private fun apply(progress: Float) {
        f = progress
        if (liftHome) { applyLift(if (toApps) f else 1f - f); return }
        val ty = transitionY()
        val showing = if (toApps) apps else home
        val hiding = if (toApps) home else apps
        // showing screen (direction-aware, HomescreenViewModel / AppscreenViewModel)
        val enter = Easing.ENTER.getInterpolation(f)
        val sIn = 0.94f + 0.06f * enter
        showing.scaleX = sIn; showing.scaleY = sIn
        showing.alpha = enter
        showing.translationY = if (toApps) ty * (1f - f) else -ty * (1f - f)
        // hiding screen
        val exit = Easing.EXIT.getInterpolation(f)
        val sOut = 1f - 0.06f * exit
        hiding.scaleX = sOut; hiding.scaleY = sOut
        hiding.alpha = 1f - exit
        hiding.translationY = if (toApps) -ty * f else ty * f
        background.set(BackgroundController.Kind.APPS, if (toApps) f else 1f - f)
    }

    /** Lift-Off at [g] (0 = Home, 1 = gone): most of the growth and blur early, the fade over 12–75%. */
    private fun applyLift(g: Float) {
        val e = 1f - (1f - g).pow(2.2f)
        home.scaleX = 1f + LIFT_SCALE * e; home.scaleY = home.scaleX
        home.translationY = 0f
        home.alpha = 1f - ((g - 0.12f) / 0.63f).coerceIn(0f, 1f)
        val r = liftBlur * e
        home.setRenderEffect(if (r < 0.5f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
        // the Apps screen only lends its background here (its grid stays hidden under the search)
        apps.scaleX = 1f; apps.scaleY = 1f; apps.translationY = 0f
        apps.alpha = g
        background.set(BackgroundController.Kind.APPS, e)
    }

    companion object {
        const val LIFT_SCALE = 0.1f
        const val LIFT_BLUR_DP = 15f
    }
}
