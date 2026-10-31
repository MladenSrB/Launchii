package com.oneuiclone.home.widgets

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.oneuiclone.home.model.AppInfo

/**
 * Two apps in split screen, [top][fromHome] above [bottom][fromHome] (0.142, rewritten from scratch).
 *
 * A third-party home can't ask System UI for a split (Pixel's launcher calls its split screen directly). All it has is
 * FLAG_ACTIVITY_LAUNCH_ADJACENT: WM puts the new task in split screen's side stage, and Shell (Android 17
 * StageCoordinator.handleRequest) answers by entering split with the top fullscreen task in the main stage. So the
 * top app has to be the top fullscreen task, with its open finished, when the bottom app goes adjacent. Started while
 * the top app's open is still running, the bottom app either joins that transition or queues behind it, and comes up
 * full screen like an app switch.
 *
 * Earlier builds waited a fixed 450 ms, a guess at how long the open takes. Now the bottom app waits for the real
 * signal, Home stopping ([homeStopped]): Home stops once the top app covers it and its open has finished (on the
 * user's Pixel 340–410 ms after the start, so no slower than the guess). [WAIT_MS] covers a top app that never covers
 * Home.
 *
 * The side stage's half is System UI's (bottom by default, top once the user has swapped apps there), so from Home
 * the two can come out the other way round. No stand-in activity is started for either app: every build that did
 * (0.133–0.139) failed on the user's phone.
 *
 * Other launchers can't wait like this (Android 17 blocks starts from a hidden app), so [QuickOpenActivity] goes the
 * other way round: one app adjacent first, then the other into the panel's half once the split has formed
 * ([openAdjacent]'s whenDone, then [openInHalf]). There the panel sits in the split itself, so it learns which half
 * the side stage is ([sideOnTop]) and puts the right app in it.
 *
 * Steps go to logcat (tag SplitLaunch) so a phone's log shows how far it got.
 */
object SplitLaunch {
    private const val TAG = "SplitLaunch"
    /** How long the bottom app waits for Home to stop before it goes anyway. */
    private const val WAIT_MS = 2000L

    private val main = Handler(Looper.getMainLooper())
    private var pending: Pending? = null

    private class Pending(val home: Activity, val bottom: AppInfo, val since: Long)

    private val timeout = Runnable { pending?.let { Log.i(TAG, "Home didn't stop in $WAIT_MS ms: bottom app anyway"); openBottom() } }

    /** From Launchii's Home: [top] now, [bottom] beside it once [top] covers Home. */
    fun fromHome(home: Activity, top: AppInfo, bottom: AppInfo) {
        cancel()
        Log.i(TAG, "top ${top.key.component.flattenToShortString()}")
        try {
            home.getSystemService(LauncherApps::class.java).startMainActivity(top.key.component, top.user, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "top failed", e)
            Toast.makeText(home, "Can't open ${top.label}", Toast.LENGTH_SHORT).show(); return
        }
        pending = Pending(home, bottom, SystemClock.uptimeMillis())
        main.postDelayed(timeout, WAIT_MS)
    }

    /** Home has stopped: the top app is in front with its open done, so the bottom app can go beside it. */
    fun homeStopped() {
        val p = pending ?: return
        Log.i(TAG, "Home stopped ${SystemClock.uptimeMillis() - p.since} ms after the top app")
        // after onStop returns, so WM has finished the stop before the next start comes in
        main.post { if (pending === p) openBottom() }
    }

    private fun openBottom() {
        val p = pending ?: return
        cancel()
        openAdjacent(p.home, p.bottom)
    }

    private fun cancel() {
        pending = null
        main.removeCallbacks(timeout)
    }

    /**
     * [app] in split screen's side stage, beside the task in front; false if it couldn't be started. [source] must be a
     * live activity (LAUNCH_ADJACENT is ignored without one) that is allowed to start activities: Home always is, any
     * other only while it's visible (Android 17 blocks background starts). [whenDone] runs once the transition this
     * start opens (the split forming) has finished ([whenTransitionEnds]).
     */
    fun openAdjacent(source: Activity, app: AppInfo, whenDone: (() -> Unit)? = null): Boolean {
        Log.i(TAG, "adjacent ${app.key.component.flattenToShortString()} from ${source.localClassName}")
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.key.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        return try { source.startActivity(intent, whenDone?.let { whenTransitionEnds(source, it) }); true } catch (e: Exception) {
            Log.w(TAG, "adjacent failed", e)
            Toast.makeText(source, "Can't open ${app.label}", Toast.LENGTH_SHORT).show(); false
        }
    }

    /**
     * Launch options that call [done] when the transition the launch is part of finishes. WM sends a custom-animation
     * launch's finish callback (the options' animationFinishedListener) from Transition.finishTransition. Only a Bundle
     * key: no hidden API. The animation itself (none, resource 0) isn't used for a task launch since Android 16, so the
     * split animates as usual.
     */
    private fun whenTransitionEnds(source: Activity, done: () -> Unit): Bundle =
        ActivityOptions.makeCustomAnimation(source, 0, 0).toBundle().apply {
            putBinder(KEY_ANIMATION_FINISHED_LISTENER, object : Binder(REMOTE_CALLBACK) {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                    Log.i(TAG, "transition finished")             // IRemoteCallback.sendResult, on a binder thread
                    main.post(done)
                    return true
                }
            })
        }

    /**
     * Whether System UI's side stage (where an adjacent start lands) is the top (or left) half, as the last split seen
     * from inside showed it ([learnSide]). Bottom until then: AOSP's default.
     */
    fun sideOnTop(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(SIDE_TOP, false)

    /**
     * [main] is an activity in the split's main stage: its half tells where the side stage is. System UI sets the
     * stages up full size and only then resizes them into halves, so full-size bounds say nothing.
     */
    fun learnSide(main: Activity) {
        if (!main.isInMultiWindowMode) return
        val metrics = main.windowManager
        val b = metrics.currentWindowMetrics.bounds
        if (b == metrics.maximumWindowMetrics.bounds) return
        val sideTop = b.top > 0 || b.left > 0                                // the main stage is the bottom/right half
        if (sideTop != sideOnTop(main)) Log.i(TAG, "side stage is on ${if (sideTop) "top" else "the bottom"}")
        main.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SIDE_TOP, sideTop).apply()
    }

    private const val PREFS = "split_widget"
    private const val SIDE_TOP = "side_top"
    private const val KEY_ANIMATION_FINISHED_LISTENER = "android:activity.animationFinishedListener"
    private const val REMOTE_CALLBACK = "android.os.IRemoteCallback"

    /**
     * [app] as a plain new task: started from inside a split, it lands in [source]'s half. [whenDone] runs once the
     * transition it opens has finished.
     */
    fun openInHalf(source: Activity, app: AppInfo, whenDone: (() -> Unit)? = null) {
        Log.i(TAG, "in half ${app.key.component.flattenToShortString()}")
        try {
            source.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.key.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                whenDone?.let { whenTransitionEnds(source, it) })
        } catch (e: Exception) {
            Log.w(TAG, "in half failed", e)
            Toast.makeText(source, "Can't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }
}
