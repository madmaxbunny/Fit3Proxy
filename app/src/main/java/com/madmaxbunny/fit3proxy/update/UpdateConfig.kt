package com.madmaxbunny.fit3proxy.update

/**
 * Hardcoded deployment endpoints for in-app updates from GitHub Releases.
 *
 * Non-system apps cannot silently install APKs — after download the user must
 * confirm once via PackageInstaller / system install UI.
 */
object UpdateConfig {
    const val GITHUB_OWNER = "madmaxbunny"
    const val GITHUB_REPO = "Fit3Proxy"

    /** GitHub Releases API — latest release JSON (preferred by the app). */
    const val LATEST_RELEASE_API =
        "https://api.github.com/repos/madmaxbunny/Fit3Proxy/releases/latest"

    /** Browser / human-facing latest release page. */
    const val LATEST_RELEASE_WEB =
        "https://github.com/madmaxbunny/Fit3Proxy/releases/latest"

    /** Companion manifest asset name expected on each release. */
    const val VERSION_JSON_ASSET = "version.json"

    const val USER_AGENT = "Fit3Proxy-Updater/1.0"
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 60_000
}
