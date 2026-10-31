package com.oneuiclone.home

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.oneuiclone.home.model.QuickOpenRecents

/** Counts notifications per package/user for One UI-style app icon badges. */
class BadgeService : NotificationListenerService() {

    companion object {
        @Volatile var counts: Map<Pair<String, UserHandle>, Int> = emptyMap(); private set
        private val listeners = mutableListOf<() -> Unit>()
        private val main = Handler(Looper.getMainLooper())
        private val notify = Runnable { listeners.toList().forEach { it() } }

        fun addListener(l: () -> Unit) { listeners += l }
        fun removeListener(l: () -> Unit) { listeners -= l }
        fun count(pkg: String, user: UserHandle) = counts[pkg to user] ?: 0

        fun isEnabled(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(ComponentName(context, BadgeService::class.java))
    }

    private val recents by lazy { QuickOpenRecents(this) }

    override fun onListenerConnected() = refresh()
    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()
    override fun onListenerDisconnected() { counts = emptyMap(); notifyListeners() }

    private fun refresh() {
        val active = try { activeNotifications } catch (_: Exception) { null } ?: return
        val m = HashMap<Pair<String, UserHandle>, Int>()
        for (sbn in active) {
            val n = sbn.notification
            // an app bubbling its own chats: the split history mustn't take its bubble over an app for split screen
            if ((n.flags and Notification.FLAG_BUBBLE) != 0) recents.bubblesItself(sbn.packageName)
            if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) continue
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
            if (n.badgeIconType == Notification.BADGE_ICON_NONE) continue
            val key = sbn.packageName to sbn.user
            m[key] = (m[key] ?: 0) + maxOf(1, n.number)
        }
        // progress / media notifications re-post constantly: only repaint icons when a count changed
        if (m == counts) return
        counts = m
        notifyListeners()
    }

    private fun notifyListeners() {
        main.removeCallbacks(notify)
        main.post(notify)                                            // coalesce bursts into one icon walk
    }
}
