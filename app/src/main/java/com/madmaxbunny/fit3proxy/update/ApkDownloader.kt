package com.madmaxbunny.fit3proxy.update

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads an APK into app cache (`cacheDir/updates/`).
 */
object ApkDownloader {

    private const val TAG = "ApkDownloader"

    fun download(
        context: Context,
        info: ReleaseInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val outFile = File(dir, info.apkAssetName)
        if (outFile.exists()) outFile.delete()

        val conn = (URL(info.apkDownloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = UpdateConfig.CONNECT_TIMEOUT_MS
            readTimeout = UpdateConfig.READ_TIMEOUT_MS
            setRequestProperty("User-Agent", UpdateConfig.USER_AGENT)
            setRequestProperty("Accept", "application/octet-stream")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("APK 다운로드 HTTP $code")
            }
            val total = conn.contentLengthLong.coerceAtLeast(-1L)
            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastReported = -1L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        // Throttle UI callbacks (~every 64KB or completion)
                        if (downloaded - lastReported >= 64 * 1024 || downloaded == total) {
                            lastReported = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                    onProgress(downloaded, if (total > 0) total else downloaded)
                }
            }
            Log.i(TAG, "Downloaded ${outFile.length()} bytes → ${outFile.absolutePath}")
            return outFile
        } finally {
            conn.disconnect()
        }
    }
}
