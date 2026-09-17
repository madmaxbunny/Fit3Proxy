package com.madmaxbunny.fit3proxy.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.madmaxbunny.fit3proxy.Fit3ProxyApp
import com.madmaxbunny.fit3proxy.R
import com.madmaxbunny.fit3proxy.session.Fit3MediaSessionManager
import com.madmaxbunny.fit3proxy.ui.MainActivity

/**
 * Foreground service with foregroundServiceType=mediaPlayback (Android 14).
 * Keeps MediaSession alive while the dashboard switch is on.
 */
class Fit3ProxyForegroundService : Service() {

    private lateinit var sessionManager: Fit3MediaSessionManager
    private var observing = false

    private val metadataObserver = { preview: Fit3MediaSessionManager.MetadataPreview ->
        if (sessionManager.isActive()) {
            updateNotification(preview.title, preview.artist)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as Fit3ProxyApp
        sessionManager = app.mediaSessionManager
        Log.i(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP")
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                Log.i(TAG, "ACTION_START / default")
                // Soft AudioFocus + session first so MediaStyle can attach token
                sessionManager.startSession()
                startAsForeground()
                if (!observing) {
                    sessionManager.metadataPreview.observeForever(metadataObserver)
                    observing = true
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        teardown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun teardown() {
        if (observing) {
            sessionManager.metadataPreview.removeObserver(metadataObserver)
            observing = false
        }
        sessionManager.stopSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startAsForeground() {
        val slot = sessionManager.currentSlot()
        val notification = buildNotification(slot.title, slot.artist)
        ServiceCompat.startForeground(
            this,
            Fit3ProxyApp.NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
        )
    }

    private fun updateNotification(title: String, artist: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(Fit3ProxyApp.NOTIFICATION_ID, buildNotification(title, artist))
    }

    private fun buildNotification(title: String, artist: String): android.app.Notification {
        val builder = NotificationCompat.Builder(this, Fit3ProxyApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("$title — $artist")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                0,
                getString(R.string.session_inactive),
                stopPendingIntent()
            )

        val token = sessionManager.getSessionToken()
        if (token != null) {
            builder.setStyle(
                MediaStyle()
                    .setMediaSession(token)
            )
        }

        return builder.build()
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, Fit3ProxyForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        return PendingIntent.getService(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "Fit3ProxyFgs"
        const val ACTION_START = "com.madmaxbunny.fit3proxy.action.START"
        const val ACTION_STOP = "com.madmaxbunny.fit3proxy.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, Fit3ProxyForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, Fit3ProxyForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            // Prefer startService so ACTION_STOP is delivered even if not running
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "stop via startService failed: ${e.message}")
                context.stopService(Intent(context, Fit3ProxyForegroundService::class.java))
            }
        }
    }
}
