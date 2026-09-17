package com.madmaxbunny.fit3proxy.update

/**
 * Parsed remote release metadata used for version comparison and APK download.
 */
data class ReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val tagName: String,
    val apkAssetName: String,
    val apkDownloadUrl: String,
    val versionJsonUrl: String? = null,
) {
    fun isNewerThan(localVersionCode: Int): Boolean = versionCode > localVersionCode
}
