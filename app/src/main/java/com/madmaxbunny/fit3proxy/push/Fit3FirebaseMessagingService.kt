package com.madmaxbunny.fit3proxy.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.madmaxbunny.fit3proxy.Fit3ProxyApp
import com.madmaxbunny.fit3proxy.notification.AlertNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives FCM token refreshes and data/notification messages.
 *
 * onMessageReceived (foreground for notification payloads; always for data-only):
 * shows emergency/high alert via [AlertNotifier] which also soft-pulses MediaSession
 * for Fit3 when the session is on. Token refresh re-registers with Push API when
 * an API key is present. Everything soft-fails — never crash the service.
 */
class Fit3FirebaseMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        try {
            Log.i(TAG, "onNewToken len=${token.length}")
            scope.launch {
                try {
                    PushTokenRegistrar.onNewToken(applicationContext, token)
                } catch (t: Throwable) {
                    Log.e(TAG, "onNewToken register soft-fail", t)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onNewToken soft-fail", t)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        try {
            Log.i(
                TAG,
                "onMessageReceived from=${message.from} " +
                    "hasNotif=${message.notification != null} dataKeys=${message.data.keys}"
            )
            val (title, body) = resolveTitleBody(message)
            // Emergency/high channel via AlertNotifier (includes pulseForAlert soft no-op).
            // Covers data-only messages (no system tray) and notification payloads in foreground.
            AlertNotifier.fireEmergencyAlert(applicationContext, title, body)
            try {
                (applicationContext as? Fit3ProxyApp)?.eventLogStore?.emit(
                    "FCM: 푸시 수신 → AlertNotifier (title=$title)"
                )
            } catch (_: Throwable) {
                // ignore
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onMessageReceived soft-fail", t)
        }
    }

    /**
     * Title/body from notification payload if present, else data keys
     * (`title`, `body`/`message`), else defaults.
     */
    private fun resolveTitleBody(message: RemoteMessage): Pair<String, String> {
        val notif = message.notification
        val data = message.data
        val title = notif?.title?.takeIf { it.isNotBlank() }
            ?: data["title"]?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TITLE
        val body = notif?.body?.takeIf { it.isNotBlank() }
            ?: data["body"]?.takeIf { it.isNotBlank() }
            ?: data["message"]?.takeIf { it.isNotBlank() }
            ?: DEFAULT_BODY
        return title to body
    }

    companion object {
        private const val TAG = "Fit3FcmService"
        private const val DEFAULT_TITLE = "Fit3Proxy"
        private const val DEFAULT_BODY = "푸시 수신"
    }
}
