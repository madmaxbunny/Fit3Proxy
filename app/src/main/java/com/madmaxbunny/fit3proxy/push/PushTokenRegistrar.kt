package com.madmaxbunny.fit3proxy.push

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.madmaxbunny.fit3proxy.Fit3ProxyApp
import kotlinx.coroutines.tasks.await

/**
 * Fetches the current FCM token and (optionally) registers it with the Push API.
 * Safe to call from background; posts status lines to [EventLogStore] when available.
 */
object PushTokenRegistrar {
    private const val TAG = "PushTokenRegistrar"

    data class Outcome(
        val token: String?,
        val registered: Boolean,
        val statusMessage: String
    )

    /**
     * @param registerIfPossible when true and API key + userId present, call Push API.
     */
    suspend fun fetchAndMaybeRegister(
        context: Context,
        registerIfPossible: Boolean = true
    ): Outcome {
        val appCtx = context.applicationContext
        val token = try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: Exception) {
            Log.w(TAG, "FCM token fetch failed", e)
            val msg = "FCM 토큰 가져오기 실패: ${e.message ?: e.javaClass.simpleName}"
            PushPrefs.setLastStatus(appCtx, msg)
            log(appCtx, msg)
            return Outcome(null, false, msg)
        }

        PushPrefs.setLastToken(appCtx, token)
        Log.i(TAG, "FCM token len=${token.length}")

        if (!registerIfPossible) {
            val msg = "FCM 토큰 확보 (등록 스킵)"
            PushPrefs.setLastStatus(appCtx, msg)
            return Outcome(token, false, msg)
        }

        if (!PushApiClient.hasApiKey()) {
            val msg = "PUSH_API_KEY 필요 — local.properties에 넣고 재빌드하세요 (토큰은 로컬에 확보됨)"
            PushPrefs.setLastStatus(appCtx, msg)
            log(appCtx, msg)
            return Outcome(token, false, msg)
        }

        val userId = PushPrefs.getUserId(appCtx)
        return when (val result = PushApiClient.registerToken(userId, token)) {
            is PushApiClient.Result.Success -> {
                val msg = "토큰 등록 성공 (HTTP ${result.httpCode}) userId=$userId"
                PushPrefs.setLastStatus(appCtx, msg)
                log(appCtx, "Push API: $msg")
                Outcome(token, true, msg)
            }
            is PushApiClient.Result.Failed -> {
                val msg = "토큰 등록 실패: ${result.reason}"
                PushPrefs.setLastStatus(appCtx, msg)
                log(appCtx, "Push API: $msg")
                Outcome(token, false, msg)
            }
        }
    }

    /** Called from [Fit3FirebaseMessagingService] when FCM rotates the token. */
    suspend fun onNewToken(context: Context, token: String) {
        val appCtx = context.applicationContext
        PushPrefs.setLastToken(appCtx, token)
        log(appCtx, "FCM onNewToken (len=${token.length}) → 재등록 시도")
        if (!PushApiClient.hasApiKey()) {
            val msg = "onNewToken: API 키 없음 — 로컬 토큰만 저장"
            PushPrefs.setLastStatus(appCtx, msg)
            log(appCtx, msg)
            return
        }
        val userId = PushPrefs.getUserId(appCtx)
        when (val result = PushApiClient.registerToken(userId, token)) {
            is PushApiClient.Result.Success -> {
                val msg = "onNewToken 재등록 성공 (HTTP ${result.httpCode})"
                PushPrefs.setLastStatus(appCtx, msg)
                log(appCtx, "Push API: $msg")
            }
            is PushApiClient.Result.Failed -> {
                val msg = "onNewToken 재등록 실패: ${result.reason}"
                PushPrefs.setLastStatus(appCtx, msg)
                log(appCtx, "Push API: $msg")
            }
        }
    }

    fun truncateToken(token: String?, head: Int = 12, tail: Int = 8): String {
        if (token.isNullOrBlank()) return "(없음)"
        if (token.length <= head + tail + 3) return token
        return "${token.take(head)}…${token.takeLast(tail)}"
    }

    private fun log(context: Context, message: String) {
        val app = context.applicationContext
        if (app is Fit3ProxyApp) {
            try {
                app.eventLogStore.emit(message)
            } catch (_: Exception) {
                Log.i(TAG, message)
            }
        } else {
            Log.i(TAG, message)
        }
    }
}
