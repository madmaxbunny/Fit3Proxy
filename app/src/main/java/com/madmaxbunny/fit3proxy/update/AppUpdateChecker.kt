package com.madmaxbunny.fit3proxy.update

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Checks GitHub Releases for a newer APK using a companion [version.json] asset
 * when present, with a tag/asset-name fallback.
 */
object AppUpdateChecker {

    private const val TAG = "AppUpdateChecker"

    sealed class Result {
        data class Available(val info: ReleaseInfo) : Result()
        data class UpToDate(val info: ReleaseInfo?) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun checkLatest(localVersionCode: Int): Result {
        return try {
            val releaseJson = fetchJson(UpdateConfig.LATEST_RELEASE_API)
                ?: return Result.Failed("releases/latest 응답 없음 (오프라인/404)")
            val info = parseRelease(releaseJson)
                ?: return Result.Failed("릴리스에서 APK/version.json 을 찾지 못함")
            if (info.isNewerThan(localVersionCode)) {
                Result.Available(info)
            } else {
                Result.UpToDate(info)
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkLatest failed", e)
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun parseRelease(root: JSONObject): ReleaseInfo? {
        val tagName = root.optString("tag_name", "")
        val assets = root.optJSONArray("assets") ?: return null

        var versionJsonUrl: String? = null
        var apkName: String? = null
        var apkUrl: String? = null

        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            val url = asset.optString("browser_download_url", "")
            if (url.isBlank()) continue
            when {
                name.equals(UpdateConfig.VERSION_JSON_ASSET, ignoreCase = true) ->
                    versionJsonUrl = url
                name.endsWith(".apk", ignoreCase = true) &&
                    name.contains("Fit3Proxy", ignoreCase = true) -> {
                    // Prefer debug APK matching Fit3Proxy-*-debug.apk pattern
                    if (apkUrl == null || name.contains("debug", ignoreCase = true)) {
                        apkName = name
                        apkUrl = url
                    }
                }
            }
        }

        val apkNameNonNull = apkName ?: return null
        val apkUrlNonNull = apkUrl ?: return null

        // Prefer companion version.json for reliable versionCode
        if (versionJsonUrl != null) {
            val vJson = fetchJson(versionJsonUrl)
            if (vJson != null) {
                val code = vJson.optInt("versionCode", -1)
                val name = vJson.optString("versionName", tagName.removePrefix("v"))
                val apkFromJson = vJson.optString("apk", vJson.optString("apkAssetName", apkNameNonNull))
                if (code > 0) {
                    // If version.json names a specific asset, try to rematch download URL
                    var resolvedUrl = apkUrlNonNull
                    var resolvedName = apkNameNonNull
                    if (apkFromJson.isNotBlank() && apkFromJson != apkName) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.optJSONObject(i) ?: continue
                            if (asset.optString("name") == apkFromJson) {
                                resolvedName = apkFromJson
                                resolvedUrl = asset.optString("browser_download_url", apkUrl)
                                break
                            }
                        }
                    }
                    return ReleaseInfo(
                        versionCode = code,
                        versionName = name.ifBlank { tagName },
                        tagName = tagName,
                        apkAssetName = resolvedName,
                        apkDownloadUrl = resolvedUrl,
                        versionJsonUrl = versionJsonUrl,
                    )
                }
            }
        }

        // Fallback: parse versionCode from release body "versionCode: N" / "versionCode `N`"
        val body = root.optString("body", "")
        val bodyCode = Regex("""versionCode[:\s`]*(\d+)""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val versionName = Regex("""versionName[:\s`]*([0-9A-Za-z._+-]+)""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.getOrNull(1)
            ?: tagName.removePrefix("v").ifBlank { apkNameNonNull }

        if (bodyCode != null && bodyCode > 0) {
            return ReleaseInfo(
                versionCode = bodyCode,
                versionName = versionName,
                tagName = tagName,
                apkAssetName = apkNameNonNull,
                apkDownloadUrl = apkUrlNonNull,
                versionJsonUrl = versionJsonUrl,
            )
        }

        // Last resort: treat as unknown code 0 so we never force-update wrongly
        Log.w(TAG, "No versionCode in version.json or release body; treating as up-to-date")
        return ReleaseInfo(
            versionCode = 0,
            versionName = versionName,
            tagName = tagName,
            apkAssetName = apkNameNonNull,
            apkDownloadUrl = apkUrlNonNull,
            versionJsonUrl = versionJsonUrl,
        )
    }

    private fun fetchJson(urlString: String): JSONObject? {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = UpdateConfig.CONNECT_TIMEOUT_MS
            readTimeout = UpdateConfig.READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", UpdateConfig.USER_AGENT)
            instanceFollowRedirects = true
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code for $urlString")
                return null
            }
            val text = BufferedReader(
                InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)
            ).use { it.readText() }
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
