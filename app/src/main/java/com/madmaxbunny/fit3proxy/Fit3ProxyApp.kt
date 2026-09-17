package com.madmaxbunny.fit3proxy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.madmaxbunny.fit3proxy.model.SlotRepository
import com.madmaxbunny.fit3proxy.session.Fit3MediaSessionManager

/**
 * Application entry — owns shared in-memory slot store and notification channel.
 */
class Fit3ProxyApp : Application() {

    lateinit var slotRepository: SlotRepository
        private set

    lateinit var mediaSessionManager: Fit3MediaSessionManager
        private set

    override fun onCreate() {
        super.onCreate()
        slotRepository = SlotRepository()
        mediaSessionManager = Fit3MediaSessionManager(this, slotRepository)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "fit3_media_session"
        const val NOTIFICATION_ID = 1001
    }
}
