package com.madmaxbunny.fit3proxy.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.madmaxbunny.fit3proxy.Fit3ProxyApp
import com.madmaxbunny.fit3proxy.R
import com.madmaxbunny.fit3proxy.ui.MainActivity

/**
 * Fires a high-priority emergency alert on the ALERT channel so Fit3
 * receives a distinct haptic (vibration on channel + notification extras).
 *
 * Galaxy Wearable / Fit3 often ignore alerts that lack an explicit vibrate
 * pattern or that look like silent updates — hence setVibrate + setSilent(false)
 * + WearableExtender, cancel-before-notify, and an optional MediaSession pulse.
 */
object AlertNotifier {

    fun fireTestEmergencyAlert(context: Context, message: String? = null) {
        val appCtx = context.applicationContext
        val title = appCtx.getString(R.string.alert_title)
        val body = message ?: appCtx.getString(R.string.alert_body_test)
        fireEmergencyAlert(appCtx, title, body)
    }

    /**
     * Show an emergency/high alert with custom title/body (FCM / manual).
     * Soft-fails: never throws to the caller.
     */
    fun fireEmergencyAlert(context: Context, title: String, body: String) {
        try {
            val appCtx = context.applicationContext
            val nm = NotificationManagerCompat.from(appCtx)

            // Force a "new" delivery to Wearable (same-id updates are often silent on Fit3)
            nm.cancel(Fit3ProxyApp.ALERT_NOTIFICATION_ID)

            val builder = NotificationCompat.Builder(appCtx, Fit3ProxyApp.ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setTicker(title) // helps some OEM bridges treat this as interruptive
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setSilent(false)
                .setVibrate(Fit3ProxyApp.ALERT_VIBRATION_PATTERN)
                .setDefaults(NotificationCompat.DEFAULT_LIGHTS)
                .setContentIntent(openAppPendingIntent(appCtx))
                .setDeleteIntent(
                    actionPendingIntent(
                        appCtx,
                        NotificationActionReceiver.ACTION_DISMISS_ALERT,
                        NotificationActionReceiver.REQ_DISMISS
                    )
                )
                .extend(
                    NotificationCompat.WearableExtender()
                        .setContentIntentAvailableOffline(true)
                )

            // Interactive actions visible on Fit3 notification detail
            builder.addAction(
                0,
                appCtx.getString(R.string.action_reboot),
                actionPendingIntent(
                    appCtx,
                    NotificationActionReceiver.ACTION_REBOOT,
                    NotificationActionReceiver.REQ_REBOOT
                )
            )
            builder.addAction(
                0,
                appCtx.getString(R.string.action_approve),
                actionPendingIntent(
                    appCtx,
                    NotificationActionReceiver.ACTION_APPROVE,
                    NotificationActionReceiver.REQ_APPROVE
                )
            )
            builder.addAction(
                0,
                appCtx.getString(R.string.action_snooze),
                actionPendingIntent(
                    appCtx,
                    NotificationActionReceiver.ACTION_SNOOZE,
                    NotificationActionReceiver.REQ_SNOOZE
                )
            )

            // Optional RemoteInput quick-reply stub
            val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY_TEXT)
                .setLabel(appCtx.getString(R.string.action_reply_hint))
                .build()
            val replyAction = NotificationCompat.Action.Builder(
                0,
                appCtx.getString(R.string.action_reply),
                replyPendingIntent(appCtx)
            )
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(false)
                .build()
            builder.addAction(replyAction)

            nm.notify(Fit3ProxyApp.ALERT_NOTIFICATION_ID, builder.build())

            // Nudge Fit3 media surface awake when session is already on (Wearable quirk)
            try {
                (appCtx as? Fit3ProxyApp)?.mediaSessionManager?.pulseForAlert()
            } catch (_: Throwable) {
                // soft no-op if session manager unreachable / off
            }

            try {
                (appCtx as? Fit3ProxyApp)?.eventLogStore?.emit(
                    "AlertNotifier: 긴급 알림 발행 (title=$title, channel=${Fit3ProxyApp.ALERT_CHANNEL_ID}, vibrate=on)"
                )
            } catch (_: Throwable) {
                // ignore log failures
            }
        } catch (t: Throwable) {
            android.util.Log.e("AlertNotifier", "fireEmergencyAlert soft-fail", t)
        }
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun actionPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** RemoteInput requires a mutable PendingIntent. */
    private fun replyPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_REPLY
        }
        return PendingIntent.getBroadcast(
            context,
            NotificationActionReceiver.REQ_REPLY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
}
