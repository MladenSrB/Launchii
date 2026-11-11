package com.oneuiclone.home

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import com.oneuiclone.home.debug.AnimLog

class LauncherActivity : Activity() {

    companion object {
        const val REQUEST_CONTACTS = 4202
    }

    lateinit var launcher: Launcher
        private set
    private var resumedOnce = false
    private var setupChecked = false
    private var lastUiMode = 0
    private lateinit var backCallback: android.window.OnBackInvokedCallback
    private var foreground = false
    private var unlockPending = false
    private var unlockDeadline = 0L
    private val unlockAttempt = Runnable { maybePlayUnlock() }
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    unlockPending = true
                    unlockDeadline = 0L
                    launcher.root.removeCallbacks(unlockAttempt)
                    launcher.onScreenLocked()
                }
                Intent.ACTION_SCREEN_ON -> maybePlayUnlock()
                Intent.ACTION_USER_PRESENT -> if (unlockPending) {
                    // Bound the attempt even when another app receives the unlock.
                    if (unlockDeadline == 0L) unlockDeadline = android.os.SystemClock.uptimeMillis() + 1500L
                    if (hasWindowFocus()) finishPendingUnlock("late user-present")
                    else maybePlayUnlock()
                }
            }
        }
    }

    private fun finishPendingUnlock(reason: String) {
        if (unlockPending) AnimLog.log("unlock reveal skipped: $reason")
        unlockPending = false
        launcher.root.removeCallbacks(unlockAttempt)
    }

    private fun maybePlayUnlock() {
        if (!unlockPending) return
        launcher.root.removeCallbacks(unlockAttempt)
        val now = android.os.SystemClock.uptimeMillis()
        if (unlockDeadline != 0L && now > unlockDeadline) { finishPendingUnlock("expired"); return }
        if (!foreground || !getSystemService(android.os.PowerManager::class.java).isInteractive ||
            launcher.root.windowVisibility != android.view.View.VISIBLE) return
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        if (hasWindowFocus() && !keyguard.isKeyguardLocked) {
            finishPendingUnlock("desktop already interactive")
            return
        }
        // Authentication can finish before the keyguard surface and input focus are handed over.
        // A non-secure swipe lock still waits for actual dismissal.
        val authenticated = !keyguard.isKeyguardLocked || (keyguard.isDeviceSecure && !keyguard.isDeviceLocked)
        if (!authenticated) {
            launcher.root.postDelayed(unlockAttempt, 64L)
            return
        }
        if (unlockDeadline == 0L) unlockDeadline = now + 1500L
        if (!launcher.readyForSetup || launcher.root.width == 0) {
            launcher.root.postDelayed(unlockAttempt, 32L)
            return
        }
        unlockPending = false
        AnimLog.log("unlock reveal starts: focus=${hasWindowFocus()} keyguard=${keyguard.isKeyguardLocked}")
        launcher.playUnlockAnimation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AnimLog.init(this)
        AppUpdate.init(this)
        AnimLog.log("activity created")
        window.setDecorFitsSystemWindows(false)
        window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        lastUiMode = resources.configuration.uiMode
        launcher = Launcher(this)
        setContentView(launcher.root)
        unlockPending = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        registerReceiver(screenReceiver, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, android.content.Context.RECEIVER_NOT_EXPORTED)
        backCallback = if (android.os.Build.VERSION.SDK_INT >= 34) createPredictiveBackCallback()
            else android.window.OnBackInvokedCallback { launcher.onBack() }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback)
    }

    @android.annotation.TargetApi(34)
    private fun createPredictiveBackCallback(): android.window.OnBackInvokedCallback =
        object : android.window.OnBackAnimationCallback {
            override fun onBackStarted(event: android.window.BackEvent) { launcher.beginPredictiveBack() }
            override fun onBackProgressed(event: android.window.BackEvent) { launcher.updatePredictiveBack(event.progress) }
            override fun onBackCancelled() { launcher.cancelPredictiveBack() }
            override fun onBackInvoked() { launcher.commitPredictiveBack() }
        }

    override fun onStart() {
        super.onStart()
        AnimLog.log("onStart")
        launcher.background.attach()
        launcher.widgets.start()
    }

    override fun onResume() {
        super.onResume()
        AnimLog.log("onResume")
        resumedOnce = true
        foreground = true
        val orientation = if (launcher.prefs.allowRotation)
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
        if (requestedOrientation != orientation) requestedOrientation = orientation
        launcher.onResume()
        // Run before posting setup work and before waiting for input focus, so the reveal overlaps entry.
        maybePlayUnlock()
        window.decorView.post { showSetupIfDue() }
    }

    override fun onPause() {
        super.onPause()
        AnimLog.log("onPause")
        foreground = false
        launcher.root.removeCallbacks(unlockAttempt)
        launcher.onPause()
    }

    override fun onStop() {
        super.onStop()
        AnimLog.log("onStop")
        launcher.background.detach()
        launcher.onStop()
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        AnimLog.log("onEnterAnimationComplete")
        if (unlockPending && foreground &&
            !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
            finishPendingUnlock("window entry completed")
        }
        launcher.closeFinished()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already showing home → collapse states (One UI behaviour).
        val alreadyOnHome = hasWindowFocus() && isResumedCompat()
        AnimLog.log("home intent alreadyOnHome=$alreadyOnHome contract=${intent.hasExtra(GestureNavContract.EXTRA_GESTURE_CONTRACT)}")
        launcher.onNewIntent(intent, alreadyOnHome)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        AnimLog.log("window focus=$hasFocus")
        if (hasFocus && ::launcher.isInitialized && unlockPending &&
            !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
            // Never fade/collapse an already revealed desktop just to replay an unlock effect.
            finishPendingUnlock("window focus already restored")
        }
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        AnimLog.log("top resumed=$isTopResumedActivity")
    }

    /**
     * First Home ever (or the first since setup was added): permissions and the wallpaper, once; or setup back in
     * front after it made us Home. Waits for Home's own first-run widget dialog, which would otherwise cover it.
     */
    fun showSetupIfDue() {
        if (isFinishing || !isResumedCompat() || !launcher.readyForSetup) return
        com.oneuiclone.home.settings.SetupActivity.resumeAfterHome(this)
        if (!setupChecked) {
            setupChecked = true
            com.oneuiclone.home.settings.SetupActivity.openOnce(this)
        }
    }

    private fun isResumedCompat(): Boolean = window.decorView.isAttachedToWindow && resumedOnce

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if ((newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) != (lastUiMode and Configuration.UI_MODE_NIGHT_MASK)) {
            launcher.onUiModeChanged()
        }
        lastUiMode = newConfig.uiMode
        launcher.requestProfileUpdate()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        launcher.widgets.onActivityResult(requestCode, resultCode)
        showSetupIfDue()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CONTACTS) {
            launcher.onContactsPermission(grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
        if (requestCode == AppBubbles.REQUEST_NOTIFICATIONS) {
            AppBubbles.onPermissionResult(this, grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
    }

    override fun onDestroy() {
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        unregisterReceiver(screenReceiver)
        launcher.root.removeCallbacks(unlockAttempt)
        launcher.widgets.stop()
        launcher.destroy()
        super.onDestroy()
    }
}
