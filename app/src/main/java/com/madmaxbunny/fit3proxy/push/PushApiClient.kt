package com.madmaxbunny.fit3proxy.push

import android.util.Log
import com.madmaxbunny.fit3proxy.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Registers / upserts FCM device tokens with the Push API
 * (https://api-push.devlion.org).
 *
 * Auth: header `X-API-Key` from [BuildConfig.PUSH_API_KEY]
 * (sourced from local.properties — never commit the real key).
 *
 * Endpoint is GET `/api/v1/push/tokens` with a JSON body
 * (`TokenRegisterRequest`: userId, deviceToken, platform). Query params alone
 * are not accepted by the current server (@RequestBody).
 */
object PushApiClient {
    private const val TAG = "PushApiClient"
    private const val PLATFORM = "ANDROID"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

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
            val url = "$base/api/v1/push/tokens"
            val json = JSONObject()
                .put("userId", userId)
                .put("deviceToken", deviceToken)
                .put("platform", PLATFORM)
                .toString()
            // Spring maps TokenRegisterRequest from JSON body on GET.
            val body = json.toRequestBody(JSON)
            val request = Request.Builder()
                .url(url)
                .get() // method overridden below via custom — OkHttp needs .method for GET+body
                .method("GET", body)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-API-Key", key)
                .build()

            http.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                Log.i(TAG, "registerToken HTTP ${resp.code} bodyLen=${respBody.length}")
                if (resp.isSuccessful) {
                    Result.Success(resp.code, respBody)
                } else {
                    Result.Failed("HTTP ${resp.code}: ${respBody.take(200)}", resp.code)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "registerToken failed", e)
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
