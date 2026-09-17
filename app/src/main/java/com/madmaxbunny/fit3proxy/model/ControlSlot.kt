package com.madmaxbunny.fit3proxy.model

/**
 * In-memory demo control slot shown on Fit3 via MediaMetadata.
 * Phase 1: no network — local state only.
 */
data class ControlSlot(
    val id: Int,
    val category: String,
    val name: String,
    var isOn: Boolean,
    var brightnessPercent: Int,
    val supportsBrightness: Boolean
) {
    val title: String
        get() = "[$category] $name"

    val artist: String
        get() = if (supportsBrightness) {
            "상태: ${if (isOn) "ON" else "OFF"} | 밝기: $brightnessPercent%"
        } else {
            "상태: ${if (isOn) "ON" else "OFF"}"
        }

    fun albumLabel(index: Int, total: Int): String =
        "Menu [${index + 1}/$total] Next로 이동"

    fun toggle() {
        isOn = !isOn
    }

    fun adjustBrightness(delta: Int) {
        if (!supportsBrightness) return
        brightnessPercent = (brightnessPercent + delta).coerceIn(0, 100)
        if (brightnessPercent > 0 && !isOn) {
            isOn = true
        }
        if (brightnessPercent == 0) {
            isOn = false
        }
    }
}
