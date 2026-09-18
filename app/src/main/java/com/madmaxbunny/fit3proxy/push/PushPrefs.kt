package com.madmaxbunny.fit3proxy.push

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists configurable Push API userId and last-known FCM token / register status.
 * Default userId is a demo placeholder — change in the dashboard UI.
 */
object PushPrefs {
    private const val PREFS = "fit3_push_prefs"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_LAST_TOKEN = "last_fcm_token"
    private const val KEY_LAST_STATUS = "last_register_status"
    const val DEFAULT_USER_ID = "fit3-demo-user"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getUserId(context: Context): String =
        prefs(context).getString(KEY_USER_ID, DEFAULT_USER_ID)?.trim().orEmpty()
            .ifBlank { DEFAULT_USER_ID }

    fun setUserId(context: Context, userId: String) {
        prefs(context).edit().putString(KEY_USER_ID, userId.trim()).apply()
    }

    fun getLastToken(context: Context): String? =
        prefs(context).getString(KEY_LAST_TOKEN, null)

    fun setLastToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_LAST_TOKEN, token).apply()
    }

    fun getLastStatus(context: Context): String =
        prefs(context).getString(KEY_LAST_STATUS, "") ?: ""

    fun setLastStatus(context: Context, status: String) {
        prefs(context).edit().putString(KEY_LAST_STATUS, status).apply()
    }
}
