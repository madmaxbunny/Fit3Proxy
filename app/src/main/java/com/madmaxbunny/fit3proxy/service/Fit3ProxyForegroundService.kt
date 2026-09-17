package com.madmaxbunny.fit3proxy.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.madmaxbunny.fit3proxy.Fit3ProxyApp
import com.madmaxbunny.fit3proxy.R
import com.madmaxbunny.fit3proxy.notification.NotificationActionReceiver
import com.madmaxbunny.fit3proxy.session.Fit3MediaSessionManager
import com.madmaxbunny.fit3proxy.ui.MainActivity

/**
 * Foreground service with foregroundServiceType=mediaPlayback (Android 14).
 * Phase 2: silent status channel + interactive notification actions for Fit3.
 *
 * IMPORTANT: Do not repost the FGS notification on every MediaSession metadata
 * change. Galaxy Wearable re-syncs each notify() to Fit3 and freezes the music
 * UI for several seconds. MediaSession metadata/state alone drive the Fit3
 * music controls; status notification text is refreshed on a long debounce.
 */
class Fit3ProxyForegroundService : Service() {

    private lateinit var sessionManager: Fit3MediaSessionManager
    private var observing = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastNotifyElapsedMs: Long = 0L
    private var pendingTitle: String? = null
    private var pendingArtist: String? = null

    private val debouncedNotifyRunnable = Runnable {
        val title = pendingTitle ?: return@Runnable
        val artist = pendingArtist ?: return@Runnable
        if (!sessionManager.isActive()) return@Runnable
        applyStatusNotification(title, artist)
    }

    private val metadataObserver = { preview: Fit3MediaSessionManager.MetadataPreview ->
        if (sessionManager.isActive()) {
            scheduleStatusNotificationUpdate(preview.title, preview.artist)
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
        mainHandler.removeCallbacks(debouncedNotifyRunnable)
        if (observing) {
            sessionManager.metadataPreview.removeObserver(metadataObserver)
            observing = false
        }
        sessionManager.stopSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startAsForeground() {
        val slot = sessionManager.currentSlot()
        val notification = buildStatusNotification(slot.title, slot.artist)
        lastNotifyElapsedMs = SystemClock.elapsedRealtime()
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

    /**
     * Coalesce rapid Next/Prev/Play presses so Wearable is not flooded.
     * Hot path only updates MediaSession (done in Fit3MediaSessionManager);
     * FGS notify is delayed until [STATUS_NOTIFY_MIN_INTERVAL_MS] has passed.
     */
    private fun scheduleStatusNotificationUpdate(title: String, artist: String) {
        pendingTitle = title
        pendingArtist = artist
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastNotifyElapsedMs
        mainHandler.removeCallbacks(debouncedNotifyRunnable)
        if (elapsed >= STATUS_NOTIFY_MIN_INTERVAL_MS) {
            // Safe to post immediately (e.g. first change after idle)
            applyStatusNotification(title, artist)
        } else {
            val delay = STATUS_NOTIFY_MIN_INTERVAL_MS - elapsed
            mainHandler.postDelayed(debouncedNotifyRunnable, delay)
        }
    }

    private fun applyStatusNotification(title: String, artist: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        // Silent channel + onlyAlertOnce → routine metadata updates must not vibrate Fit3
        nm.notify(Fit3ProxyApp.NOTIFICATION_ID, buildStatusNotification(title, artist))
        lastNotifyElapsedMs = SystemClock.elapsedRealtime()
        Log.d(TAG, "status notification refreshed (debounced) title=$title")
    }

    private fun buildStatusNotification(title: String, artist: String): android.app.Notification {
        val builder = NotificationCompat.Builder(this, Fit3ProxyApp.STATUS_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("$title — $artist")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Interactive actions (1–3) for Fit3 notification detail
            .addAction(
                0,
                getString(R.string.action_reboot),
                actionPendingIntent(
                    NotificationActionReceiver.ACTION_REBOOT,
                    NotificationActionReceiver.REQ_REBOOT
                )
            )
            .addAction(
                0,
                getString(R.string.action_approve),
                actionPendingIntent(
                    NotificationActionReceiver.ACTION_APPROVE,
                    NotificationActionReceiver.REQ_APPROVE
                )
            )
            .addAction(
                0,
                getString(R.string.action_snooze),
                actionPendingIntent(
                    NotificationActionReceiver.ACTION_SNOOZE,
                    NotificationActionReceiver.REQ_SNOOZE
                )
            )

        val token = sessionManager.getSessionToken()
        if (token != null) {
            // Compact view stays MediaSession transport; expanded shows custom actions
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

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, NotificationActionReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "Fit3ProxyFgs"
        const val ACTION_START = "com.madmaxbunny.fit3proxy.action.START"
        const val ACTION_STOP = "com.madmaxbunny.fit3proxy.action.STOP"

        /** Min gap between FGS notify() calls — prevents Fit3 freeze on rapid media keys. */
        private const val STATUS_NOTIFY_MIN_INTERVAL_MS = 2_500L

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
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "stop via startService failed: ${e.message}")
                context.stopService(Intent(context, Fit3ProxyForegroundService::class.java))
            }
        }
    }
}
