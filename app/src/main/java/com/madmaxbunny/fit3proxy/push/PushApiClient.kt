package com.madmaxbunny.fit3proxy.push

import android.util.Log
import com.madmaxbunny.fit3proxy.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Registers / upserts FCM device tokens with the Push API
 * (https://api-push.devlion.org).
 *
 * Auth: header `X-API-Key` from [BuildConfig.PUSH_API_KEY]
 * (sourced from local.properties — never commit the real key).
 *
 * Spring-style GET: `/api/v1/push/tokens?userId=&deviceToken=&platform=ANDROID`
 */
object PushApiClient {
    private const val TAG = "PushApiClient"
    private const val PLATFORM = "ANDROID"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    sealed class Result {
        data class Success(val httpCode: Int, val body: String) : Result()
        data class Failed(val reason: String, val httpCode: Int? = null) : Result()
    }

    fun hasApiKey(): Boolean = BuildConfig.PUSH_API_KEY.isNotBlank()

    fun registerToken(userId: String, deviceToken: String): Result {
        val key = BuildConfig.PUSH_API_KEY
        if (key.isBlank()) {
            return Result.Failed("PUSH_API_KEY 없음 — local.properties에 설정 후 재빌드하세요")
        }
        if (userId.isBlank()) {
            return Result.Failed("userId가 비어 있습니다")
        }
        if (deviceToken.isBlank()) {
            return Result.Failed("deviceToken이 비어 있습니다")
        }

        return try {
            val base = BuildConfig.PUSH_API_BASE_URL.trimEnd('/')
            val qUser = URLEncoder.encode(userId, StandardCharsets.UTF_8.name())
            val qToken = URLEncoder.encode(deviceToken, StandardCharsets.UTF_8.name())
            val qPlatform = URLEncoder.encode(PLATFORM, StandardCharsets.UTF_8.name())
            val url = URL(
                "$base/api/v1/push/tokens?userId=$qUser&deviceToken=$qToken&platform=$qPlatform"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-API-Key", key)
                instanceFollowRedirects = true
            }
            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.use { input ->
                    BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
                }.orEmpty()
                Log.i(TAG, "registerToken HTTP $code bodyLen=${body.length}")
                if (code in 200..299) {
                    Result.Success(code, body)
                } else {
                    Result.Failed("HTTP $code: ${body.take(200)}", code)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "registerToken failed", e)
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
