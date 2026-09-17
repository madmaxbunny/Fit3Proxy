package com.madmaxbunny.fit3proxy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Color
import android.os.Build
import com.madmaxbunny.fit3proxy.log.EventLogStore
import com.madmaxbunny.fit3proxy.model.SlotRepository
import com.madmaxbunny.fit3proxy.session.Fit3MediaSessionManager

/**
 * Application entry — owns shared in-memory stores and notification channels.
 *
 * Phase 2 channels:
 *  - STATUS (silent / LOW): ongoing FGS — setOnlyAlertOnce, no vibration
 *  - ALERT (HIGH): emergency haptic with distinct vibration pattern
 *
 * Note: Android freezes channel importance/vibration after first create.
 * ALERT_CHANNEL_ID is versioned (…_v2) so upgrades pick up corrected settings.
 */
class Fit3ProxyApp : Application() {

    lateinit var slotRepository: SlotRepository
        private set

    lateinit var mediaSessionManager: Fit3MediaSessionManager
        private set

    lateinit var eventLogStore: EventLogStore
        private set

    override fun onCreate() {
        super.onCreate()
        slotRepository = SlotRepository()
        eventLogStore = EventLogStore()
        mediaSessionManager = Fit3MediaSessionManager(this, slotRepository)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        // Drop legacy alert channel id if present (importance/vibrate immutable after create)
        try {
            nm.deleteNotificationChannel(ALERT_CHANNEL_ID_LEGACY)
        } catch (_: Exception) {
            // ignore
        }

        val statusChannel = NotificationChannel(
            STATUS_CHANNEL_ID,
            getString(R.string.status_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.status_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            enableLights(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        // Distinct emergency haptic for Fit3 (and phone): short-long-short pattern
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.alert_channel_desc)
            setShowBadge(true)
            enableVibration(true)
            vibrationPattern = ALERT_VIBRATION_PATTERN
            enableLights(true)
            lightColor = Color.RED
            setSound(null, null) // haptic-focused; avoid phone ringtone fighting Wearable
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setAllowBubbles(false)
            }
            // Interruptive even under DND when policy allows (no-op without ACCESS_NOTIFICATION_POLICY)
            setBypassDnd(true)
        }

        nm.createNotificationChannel(statusChannel)
        nm.createNotificationChannel(alertChannel)
    }

    companion object {
        /** Ongoing silent FGS status channel (routine updates must not buzz Fit3). */
        const val STATUS_CHANNEL_ID = "fit3_status_silent"
        /** @deprecated Use [STATUS_CHANNEL_ID]; kept for older installs that may still reference it. */
        const val CHANNEL_ID = STATUS_CHANNEL_ID

        /** Legacy id from 0.2.0 — deleted on upgrade so Wearable sees a fresh HIGH channel. */
        const val ALERT_CHANNEL_ID_LEGACY = "fit3_alert_emergency"
        const val ALERT_CHANNEL_ID = "fit3_alert_emergency_v2"

        const val NOTIFICATION_ID = 1001
        const val ALERT_NOTIFICATION_ID = 1002

        /** ms: wait, vibrate, pause, vibrate, pause, vibrate */
        val ALERT_VIBRATION_PATTERN = longArrayOf(0, 400, 200, 400, 200, 800)
    }
}
