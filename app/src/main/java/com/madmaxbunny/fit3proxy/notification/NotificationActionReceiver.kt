package com.madmaxbunny.fit3proxy.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.madmaxbunny.fit3proxy.Fit3ProxyApp

/**
 * Handles Fit3 / phone notification action buttons and optional RemoteInput reply.
 * Phase 2: log only — no network/MQTT (Phase 3).
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val app = context.applicationContext as? Fit3ProxyApp
        val log = app?.eventLogStore

        when (intent.action) {
            ACTION_REBOOT -> {
                Log.i(TAG, "ACTION_REBOOT")
                log?.emit("NotificationAction: [재부팅] 클릭 — (Phase 2 stub, no network)")
            }
            ACTION_APPROVE -> {
                Log.i(TAG, "ACTION_APPROVE")
                log?.emit("NotificationAction: [승인] 클릭 — (Phase 2 stub, no network)")
            }
            ACTION_SNOOZE -> {
                Log.i(TAG, "ACTION_SNOOZE")
                log?.emit("NotificationAction: [스누즈] 클릭 — 긴급 알림 해제")
                NotificationManagerCompat.from(context)
                    .cancel(Fit3ProxyApp.ALERT_NOTIFICATION_ID)
            }
            ACTION_REPLY -> {
                val reply = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_REPLY_TEXT)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                Log.i(TAG, "ACTION_REPLY text=$reply")
                log?.emit(
                    if (reply.isEmpty()) {
                        "NotificationAction: [답장] (빈 입력)"
                    } else {
                        "NotificationAction: [답장] \"$reply\" — (Phase 2 RemoteInput stub)"
                    }
                )
                NotificationManagerCompat.from(context)
                    .cancel(Fit3ProxyApp.ALERT_NOTIFICATION_ID)
            }
            ACTION_DISMISS_ALERT -> {
                Log.i(TAG, "ACTION_DISMISS_ALERT")
                log?.emit("NotificationAction: 긴급 알림 닫힘")
                NotificationManagerCompat.from(context)
                    .cancel(Fit3ProxyApp.ALERT_NOTIFICATION_ID)
            }
            else -> {
                Log.w(TAG, "Unknown action=${intent.action}")
            }
        }
    }

    companion object {
        private const val TAG = "Fit3NotifAction"

        const val ACTION_REBOOT = "com.madmaxbunny.fit3proxy.action.REBOOT"
        const val ACTION_APPROVE = "com.madmaxbunny.fit3proxy.action.APPROVE"
        const val ACTION_SNOOZE = "com.madmaxbunny.fit3proxy.action.SNOOZE"
        const val ACTION_REPLY = "com.madmaxbunny.fit3proxy.action.REPLY"
        const val ACTION_DISMISS_ALERT = "com.madmaxbunny.fit3proxy.action.DISMISS_ALERT"

        const val KEY_REPLY_TEXT = "fit3_reply_text"

        /** Request codes for PendingIntent uniqueness. */
        const val REQ_REBOOT = 201
        const val REQ_APPROVE = 202
        const val REQ_SNOOZE = 203
        const val REQ_REPLY = 204
        const val REQ_DISMISS = 205
    }
}
