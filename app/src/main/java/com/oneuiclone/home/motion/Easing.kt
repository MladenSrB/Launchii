package com.oneuiclone.home.motion

import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

/**
 * Easing curves taken from One UI Home 17.5 / SESL (see ONEUI85_EXTRACTED_SPEC.md §2).
 */
object Easing {
    /** One UI "basic" curve: popups, items, task list. */
    val BASIC: Interpolator = PathInterpolator(0.22f, 0.25f, 0f, 1f)
    val BASIC_05: Interpolator = PathInterpolator(0.22f, 0.5f, 0f, 1f)

    val SINE_IN_OUT_33: Interpolator = PathInterpolator(0.33f, 0f, 0.67f, 1f)
    val SINE_IN_OUT_60: Interpolator = PathInterpolator(0.33f, 0f, 0.4f, 1f)
    val SINE_IN_OUT_70: Interpolator = PathInterpolator(0.33f, 0f, 0.3f, 1f)
    val SINE_IN_OUT_80: Interpolator = PathInterpolator(0.33f, 0f, 0.2f, 1f)
    val SINE_IN_OUT_90: Interpolator = PathInterpolator(0.33f, 0f, 0.1f, 1f)
    val SINE_OUT_60: Interpolator = PathInterpolator(0.17f, 0.17f, 0.4f, 1f)
    val SINE_OUT_70: Interpolator = PathInterpolator(0.17f, 0.17f, 0.3f, 1f)
    val SINE_OUT_80: Interpolator = PathInterpolator(0.17f, 0.17f, 0.2f, 1f)
    val SINE_OUT_90: Interpolator = PathInterpolator(0.17f, 0.17f, 0.1f, 1f)
    val SINE_IN_70: Interpolator = PathInterpolator(0.7f, 0f, 0.83f, 0.83f)
    val EASE_IN_OUT: Interpolator = PathInterpolator(0.42f, 0f, 0.58f, 1f)

    /** InsetsController.SYNC_IME_INTERPOLATOR (285 ms): the soft keyboard's own show / hide curve. */
    val IME: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    const val IME_DURATION = 285L

    /** Launcher3 standard_interpolator (pop-up folder open/close). */
    val STANDARD: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** Folder panel morph + preview icon flight (N5.a.b). */
    val FOLDER: Interpolator = PathInterpolator(0.17f, 0.34f, 0.3f, 1f)

    /** HighEndOpenParams. */
    val APP_OPEN_WINDOW: Interpolator = PathInterpolator(0.2f, 0.94f, 0.05f, 1f)
    val APP_OPEN_HOME: Interpolator = PathInterpolator(0.4f, 0f, 0f, 1f)
    val APP_OPEN_WALLPAPER: Interpolator = PathInterpolator(0.05f, 0.3f, 0f, 1f)

    /** HighEndCloseParams. */
    val APP_CLOSE_POSITION: Interpolator = PathInterpolator(0.33f, 0f, 0.1f, 1f)
    val APP_CLOSE_WINDOW_ALPHA: Interpolator = PathInterpolator(0.83f, -0.32f, 0.02f, 1.36f)

    // iOS-inspired app motion: prompt response, then a longer, soft settling tail.
    // These are launcher tuning values, not measured UIKit timing curves.
    val APP_LAUNCH: Interpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    val APP_RETURN: Interpolator = PathInterpolator(0.22f, 0.78f, 0.2f, 1f)
    val APP_BLUR_IN: Interpolator = PathInterpolator(0.2f, 0.7f, 0.2f, 1f)
    val APP_BLUR_OUT: Interpolator = PathInterpolator(0.35f, 0f, 0.25f, 1f)

    /** Blur forward / reverse used by app transitions. */
    val BLUR_FORWARD: Interpolator = PathInterpolator(0f, 0.6f, 0.4f, 1f)
    val BLUR_REVERSE: Interpolator = PathInterpolator(0.75f, 0f, 1f, 0.8f)

    val ICON_DIM: Interpolator = PathInterpolator(0.25f, 0.3f, 0f, 1f)

    /** 8.5 touch ripple – overshoots. */
    val RIPPLE: Interpolator = PathInterpolator(0.807f, 0.586f, 0.266f, 1.628f)

    /** DragVIProvider. */
    val DRAG_SCALE_DOWN: Interpolator = PathInterpolator(0.6f, 0f, 0.4f, 1f)
    val DRAG_SCALE_UP: Interpolator = PathInterpolator(0.16f, 0f, 0.4f, 1f)
    val DRAG_TRANSITION: Interpolator = PathInterpolator(0.64f, 0f, 0.67f, 1f)

    val LINEAR: Interpolator = Interpolator { it }
    val DECEL_2: Interpolator = DecelerateInterpolator(2f)

    /** (t-1)^3+1 — screen transitions on release. */
    val CUBIC_OUT: Interpolator = Interpolator { t -> val f = t - 1f; f * f * f + 1f }

    /** (t-1)^5+1 — screen transitions on fast fling, page scroller. */
    val QUINT_OUT: Interpolator = Interpolator { t -> val f = t - 1f; f * f * f * f * f + 1f }

    /** InterpolatorUtil.EnterTransitionInterpolator: 0 until 33 %, then linear. */
    val ENTER: Interpolator = Interpolator { t -> if (t <= 0.33f) 0f else ((t - 0.33f) / 0.67f).coerceIn(0f, 1f) }

    /** InterpolatorUtil.ExitTransitionInterpolator: linear over first 40 %, then 1. */
    val EXIT: Interpolator = Interpolator { t -> if (t < 0.4f) t / 0.4f else 1f }

    /**
     * A unit-mass spring (stiffness [k], damping ratio [z]) as an interpolator for an animator of [durationMs]:
     * progress after fraction × [durationMs] of the spring's motion, which may overshoot. Ends exactly at 1.
     */
    fun spring(k: Float, z: Float, durationMs: Long): Interpolator {
        val w = kotlin.math.sqrt(k)
        val wd = w * kotlin.math.sqrt(maxOf(0f, 1f - z * z))
        return Interpolator { f ->
            if (f >= 1f) return@Interpolator 1f
            val t = f * durationMs / 1000f
            if (z >= 1f) 1f - kotlin.math.exp(-w * t) * (1f + w * t)          // critically damped (no overshoot)
            else 1f - kotlin.math.exp(-z * w * t) * (kotlin.math.cos(wd * t) + (z * w / wd) * kotlin.math.sin(wd * t))
        }
    }

    fun clampToProgress(i: Interpolator, lower: Float, upper: Float): Interpolator = Interpolator { p ->
        when {
            p < lower -> 0f
            p > upper -> 1f
            else -> i.getInterpolation((p - lower) / (upper - lower))
        }
    }

    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

/** Spring constants from One UI Home (spec §6). Damping is a damping ratio. */
object Springs {
    const val CELL_SCALE_STIFFNESS = 200f
    const val CELL_SCALE_DAMPING = 0.78f
    const val PAGE_EDIT_ENTER_STIFFNESS = 225f
    const val PAGE_EDIT_ENTER_DAMPING = 0.67f
    const val PAGE_EDIT_EXIT_STIFFNESS = 360f
    const val PAGE_EDIT_EXIT_DAMPING = 0.99f
    const val ICON_MOVE_STIFFNESS = 140f
    const val ICON_MOVE_DAMPING = 0.82f
    const val ICON_SCALE_STIFFNESS = 260f
    const val ICON_SCALE_DAMPING = 0.96f
    const val FOLDER_ICON_STIFFNESS = 200f
    const val FOLDER_ICON_DAMPING = 0.6f
}
