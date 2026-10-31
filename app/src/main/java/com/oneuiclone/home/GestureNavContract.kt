package com.oneuiclone.home

import android.content.ComponentName
import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import android.view.SurfaceControl

/**
 * Port of AOSP Launcher3's GestureNavContract: when the user swipes an app home, the system's recents
 * provider sends our home intent with this contract so we can tell it where the app's icon lives. The
 * window then shrinks into that rect (One UI's "window → icon" close).
 */
class GestureNavContract(
    val componentName: ComponentName,
    val user: UserHandle,
    private val callback: Message,
) {
    companion object {
        const val EXTRA_GESTURE_CONTRACT = "gesture_nav_contract_v1"
        const val EXTRA_ICON_POSITION = "gesture_nav_contract_icon_position"
        const val EXTRA_ICON_SURFACE = "gesture_nav_contract_surface_control"
        const val EXTRA_REMOTE_CALLBACK = "android.intent.extra.REMOTE_CALLBACK"
        const val EXTRA_ON_FINISH_CALLBACK = "gesture_nav_contract_finish_callback"

        private var finishListener: (() -> Unit)? = null
        private var finishId = 0
        /**
         * The finish callback crosses into the recents process, so it must reply through a Messenger:
         * Quickstep calls `callback.replyTo.send(callback)` and crashes on a bare Handler message.
         */
        private val finishMessenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
            if (msg.what == finishId) { finishListener?.invoke(); finishListener = null }
            true
        })

        @Suppress("DEPRECATION")
        fun fromIntent(intent: Intent): GestureNavContract? {
            val extras = intent.getBundleExtra(EXTRA_GESTURE_CONTRACT) ?: return null
            intent.removeExtra(EXTRA_GESTURE_CONTRACT)
            val cn = extras.getParcelable<ComponentName>(Intent.EXTRA_COMPONENT_NAME) ?: return null
            val user = extras.getParcelable<UserHandle>(Intent.EXTRA_USER) ?: return null
            val cb = extras.getParcelable<Message>(EXTRA_REMOTE_CALLBACK) ?: return null
            if (cb.replyTo == null) return null
            return GestureNavContract(cn, user, cb)
        }
    }

    /** @param onFinish called when the system has finished animating into the icon. */
    fun sendEndPosition(position: RectF, surface: SurfaceControl?, onFinish: () -> Unit) {
        val result = Bundle()
        result.putParcelable(EXTRA_ICON_POSITION, position)
        result.putParcelable(EXTRA_ICON_SURFACE, surface)
        finishListener = onFinish
        result.putParcelable(EXTRA_ON_FINISH_CALLBACK, Message.obtain().apply { what = ++finishId; replyTo = finishMessenger })
        val msg = Message.obtain()
        msg.copyFrom(callback)
        msg.data = result
        try {
            callback.replyTo.send(msg)
        } catch (e: RemoteException) {
            Log.e("GestureNavContract", "send failed", e)
        }
    }
}
