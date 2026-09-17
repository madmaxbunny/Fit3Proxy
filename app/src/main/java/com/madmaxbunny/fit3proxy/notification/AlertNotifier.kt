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
 * receives a distinct haptic (vibration pattern on the channel).
 */
object AlertNotifier {

    fun fireTestEmergencyAlert(context: Context, message: String? = null) {
        val appCtx = context.applicationContext
        val title = appCtx.getString(R.string.alert_title)
        val body = message ?: appCtx.getString(R.string.alert_body_test)

        val builder = NotificationCompat.Builder(appCtx, Fit3ProxyApp.ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(openAppPendingIntent(appCtx))
            .setDeleteIntent(actionPendingIntent(appCtx, NotificationActionReceiver.ACTION_DISMISS_ALERT, NotificationActionReceiver.REQ_DISMISS))

        // Interactive actions visible on Fit3 notification detail
        builder.addAction(
            0,
            appCtx.getString(R.string.action_reboot),
            actionPendingIntent(appCtx, NotificationActionReceiver.ACTION_REBOOT, NotificationActionReceiver.REQ_REBOOT)
        )
        builder.addAction(
            0,
            appCtx.getString(R.string.action_approve),
            actionPendingIntent(appCtx, NotificationActionReceiver.ACTION_APPROVE, NotificationActionReceiver.REQ_APPROVE)
        )
        builder.addAction(
            0,
            appCtx.getString(R.string.action_snooze),
            actionPendingIntent(appCtx, NotificationActionReceiver.ACTION_SNOOZE, NotificationActionReceiver.REQ_SNOOZE)
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

        NotificationManagerCompat.from(appCtx)
            .notify(Fit3ProxyApp.ALERT_NOTIFICATION_ID, builder.build())

        (appCtx as? Fit3ProxyApp)?.eventLogStore?.emit(
            "AlertNotifier: 긴급 알림 발행 (channel=${Fit3ProxyApp.ALERT_CHANNEL_ID})"
        )
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
