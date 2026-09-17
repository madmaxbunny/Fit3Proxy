package com.madmaxbunny.fit3proxy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
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
        }

        nm.createNotificationChannel(statusChannel)
        nm.createNotificationChannel(alertChannel)
    }

    companion object {
        /** Ongoing silent FGS status channel (routine updates must not buzz Fit3). */
        const val STATUS_CHANNEL_ID = "fit3_status_silent"
        /** @deprecated Use [STATUS_CHANNEL_ID]; kept for older installs that may still reference it. */
        const val CHANNEL_ID = STATUS_CHANNEL_ID

        const val ALERT_CHANNEL_ID = "fit3_alert_emergency"

        const val NOTIFICATION_ID = 1001
        const val ALERT_NOTIFICATION_ID = 1002

        /** ms: wait, vibrate, pause, vibrate, pause, vibrate */
        val ALERT_VIBRATION_PATTERN = longArrayOf(0, 400, 200, 400, 200, 800)
    }
}
