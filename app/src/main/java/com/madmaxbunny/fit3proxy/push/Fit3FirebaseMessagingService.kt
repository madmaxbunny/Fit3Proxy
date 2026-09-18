package com.madmaxbunny.fit3proxy.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives FCM token refreshes and (optionally) data messages.
 * Token refresh triggers Push API re-registration when API key is present.
 */
class Fit3FirebaseMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "onNewToken len=${token.length}")
        scope.launch {
            PushTokenRegistrar.onNewToken(applicationContext, token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.i(
            TAG,
            "onMessageReceived from=${message.from} dataKeys=${message.data.keys}"
        )
        // Delivery / display can be wired later; token register is the 0.6.0 focus.
    }

    companion object {
        private const val TAG = "Fit3FcmService"
    }
}
