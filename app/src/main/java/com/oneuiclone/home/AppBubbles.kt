package com.oneuiclone.home

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import com.oneuiclone.home.debug.AnimLog
import com.oneuiclone.home.model.AppInfo
import com.oneuiclone.home.model.QuickOpenRecents

/**
 * The quick options' "Bubble": opens an app in a floating bubble, as the Pixel Launcher's does. Pixel's goes
 * through System UI's IBubbles.showAppBubble, which only the system's recents launcher can reach, so this posts
 * a conversation bubble instead (the public API): a long-lived shortcut per app whose intent is the app's main
 * activity, and a MessagingStyle notification on it that expands at once with its own notification hidden.
 * NotificationManagerService only checks that the bubble's activity is resizable (BubbleExtractor), not whose it
 * is. Android 16+ System UI starts the bubble from the shortcut (BubbleTaskViewHelper), older ones from the
 * PendingIntent, so both point at the app.
 *
 * Publishing shortcuts needs a MAIN/LAUNCHER activity of our own (ShortcutService), hence the settings alias in
 * the manifest; our own Apps screen skips our package, so it only shows in other launchers.
 */
object AppBubbles {
    private const val CHANNEL = "app_bubbles"
    private const val ID = 1
    const val REQUEST_NOTIFICATIONS = 4203

    /** Work-profile apps can't be started from our shortcuts (they're published in our profile). */
    fun supports(app: AppInfo) = app.user == Process.myUserHandle()

    /** Notifications allowed, bubbles on, and every Launchii conversation may bubble ("All conversations"). */
    fun ready(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        return nm.areNotificationsEnabled() && nm.areBubblesEnabled() &&
            nm.bubblePreference == NotificationManager.BUBBLE_PREFERENCE_ALL
    }

    /** What's still missing, for the setting's summary (null once [ready]). */
    fun missing(context: Context): String? {
        val nm = context.getSystemService(NotificationManager::class.java)
        return when {
            !nm.areNotificationsEnabled() -> "Allow notifications for ${context.getString(R.string.app_name)} to use bubbles"
            !nm.areBubblesEnabled() || nm.bubblePreference != NotificationManager.BUBBLE_PREFERENCE_ALL ->
                "Set bubbles for ${context.getString(R.string.app_name)} to All conversations can bubble"
            else -> null
        }
    }

    /**
     * One step of setup: the notification permission (asked once, then the app's notification settings), then
     * the app's bubble settings. [onPermissionResult] carries on after the permission dialog.
     */
    fun setUp(activity: Activity) {
        val nm = activity.getSystemService(NotificationManager::class.java)
        val sp = activity.getSharedPreferences("home_settings", Context.MODE_PRIVATE)
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        when {
            activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED &&
                (!sp.getBoolean("asked_notifications", false) || activity.shouldShowRequestPermissionRationale(permission)) -> {
                sp.edit().putBoolean("asked_notifications", true).apply()
                activity.requestPermissions(arrayOf(permission), REQUEST_NOTIFICATIONS)
            }
            !nm.areNotificationsEnabled() -> open(activity, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS))
            !ready(activity) -> open(activity, Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS))
        }
    }

    fun onPermissionResult(activity: Activity, granted: Boolean) {
        if (granted && !ready(activity)) open(activity, Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS))
    }

    private fun open(activity: Activity, intent: Intent) {
        try {
            activity.startActivity(intent.putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
        } catch (_: Exception) {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", activity.packageName, null)))
        }
    }

    private fun key(app: AppInfo) = "bubble:" + app.key.component.flattenToShortString()

    /**
     * Whether a start from Home would take [app] out of its bubble: its activity is singleTask or singleInstance, so it
     * has one task, and that's the bubble's. Android moves it full screen and the bubble is left blank (Skipii up to
     * 0.5.32, seen on the phone). Such an app's bubble is opened instead; other apps start in a task of their own.
     */
    fun inSingleTaskBubble(context: Context, app: AppInfo): Boolean {
        val mode = app.activityInfo.activityInfo.launchMode
        if (mode != android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK && mode != android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE) return false
        val key = key(app)
        return context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.tag == key && it.id == ID && it.notification.flags and Notification.FLAG_BUBBLE != 0 }
    }

    /**
     * Posts [app]'s bubble, opened at once unless [expand] is false (it then waits in the stack); [onShown] runs once
     * the system has made it a bubble. If the system turns it down (the app isn't resizable, bubbles got switched off)
     * the notification is withdrawn and [fallback] runs instead. Exactly one of the two runs. Posting a bubble that's
     * already up opens it.
     */
    fun show(context: Context, app: AppInfo, expand: Boolean = true, onShown: (() -> Unit)? = null, fallback: () -> Unit) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val key = key(app)
        val label = app.label
        try {
            val icon = adaptiveIcon(context, app)
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.key.component)
            val person = Person.Builder().setName(label).setIcon(icon).setKey(key).build()
            context.getSystemService(ShortcutManager::class.java).pushDynamicShortcut(
                ShortcutInfo.Builder(context, key)
                    .setShortLabel(label)
                    .setIcon(icon)
                    .setIntent(launch)
                    .setPerson(person)
                    .setLongLived(true)
                    .build())
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "App bubbles", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Apps you open in a bubble from the Home screen"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                })
            }
            // a bubble the user dismissed can leave its notification behind, and that one won't bubble again
            nm.activeNotifications.firstOrNull { it.tag == key && it.id == ID && it.notification.flags and Notification.FLAG_BUBBLE == 0 }
                ?.let { nm.cancel(key, ID) }
            val pi = PendingIntent.getActivity(context, key.hashCode(), launch,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val metadata = Notification.BubbleMetadata.Builder(pi, icon)
                .setDesiredHeight(context.resources.displayMetrics.heightPixels)     // capped at the tallest bubble
                .setAutoExpandBubble(expand)
                .setSuppressNotification(true)
                .build()
            val me = Person.Builder().setName("You").build()
            nm.notify(key, ID, Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_mono)
                .setContentTitle(label)
                .setContentText("Open in a bubble")
                .setContentIntent(pi)
                .setShortcutId(key)
                .setStyle(Notification.MessagingStyle(me).addMessage("Open in a bubble", System.currentTimeMillis(), person))
                .setBubbleMetadata(metadata)
                .setOnlyAlertOnce(true)
                .build())
        } catch (e: Exception) {
            AnimLog.log("bubble $label: couldn't post (${e.javaClass.simpleName}: ${e.message})")
            fallback()
            return
        }
        // getActiveNotifications also returns enqueued notifications, before the ranking (BubbleExtractor) has set
        // FLAG_BUBBLE, and posting waits ~200 ms for the notification assistant; so only judge after that
        val handler = Handler(Looper.getMainLooper())
        var tries = 0
        lateinit var check: Runnable
        check = Runnable {
            val sbn = nm.activeNotifications.firstOrNull { it.tag == key && it.id == ID }
            when {
                sbn != null && sbn.notification.flags and Notification.FLAG_BUBBLE != 0 -> {
                    AnimLog.log("bubble $label: shown")
                    QuickOpenRecents(context).bubbled(app)
                    onShown?.invoke()
                }
                ++tries < 8 -> handler.postDelayed(check, 150)
                sbn != null -> {
                    AnimLog.log("bubble $label: not allowed to bubble, opening normally")
                    nm.cancel(key, ID)
                    fallback()
                }
                else -> { AnimLog.log("bubble $label: never posted, opening normally"); fallback() }
            }
        }
        handler.postDelayed(check, 150)
    }

    /** The app's icon as an adaptive bitmap (108dp, both layers), so System UI can mask it like any bubble. */
    private fun adaptiveIcon(context: Context, app: AppInfo): Icon {
        val size = (108 * context.resources.displayMetrics.density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val d = app.activityInfo.getIcon(context.resources.displayMetrics.densityDpi)
        if (d is AdaptiveIconDrawable) {
            d.background?.apply { setBounds(0, 0, size, size); draw(c) }
            d.foreground?.apply { setBounds(0, 0, size, size); draw(c) }
        } else {
            // a legacy icon sits on white inside the 72dp safe zone
            c.drawColor(Color.WHITE)
            val inset = size * 22 / 108
            d.setBounds(inset, inset, size - inset, size - inset)
            d.draw(c)
        }
        return Icon.createWithAdaptiveBitmap(bmp)
    }
}
