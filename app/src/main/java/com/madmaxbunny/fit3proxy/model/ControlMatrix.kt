package com.madmaxbunny.fit3proxy.model

/**
 * 2D control matrix: rows = slots (Next/Prev), cols = per-slot level (Volume ↑/↓).
 *
 * Each row remembers its own column independently ([levelBySlot]).
 * Cell (slotIndex, levelIndex) is the active control point.
 */
class ControlMatrix(
    private val slots: MutableList<ControlSlot>
) {

    /** Column indices 0..[LEVEL_MAX_INDEX] → percent = index * [LEVEL_STEP_PERCENT]. */
    companion object {
        const val LEVEL_MAX_INDEX = 10
        const val LEVEL_STEP_PERCENT = 10
        const val LEVEL_PERCENT_MAX = LEVEL_MAX_INDEX * LEVEL_STEP_PERCENT // 100
    }

    private var slotIndex: Int = 0

    /** Independent column per row; default mid (50%). */
    private val levelBySlot: IntArray = IntArray(slots.size) { 5 }

    val rowCount: Int get() = slots.size

    val colCount: Int get() = LEVEL_MAX_INDEX + 1

    val currentSlotIndex: Int get() = slotIndex

    fun currentSlot(): ControlSlot = slots[slotIndex]

    fun currentLevelIndex(): Int = levelBySlot[slotIndex]

    /** Display / remVol-style value 0..100 step 10. */
    fun currentLevelPercent(): Int = currentLevelIndex() * LEVEL_STEP_PERCENT

    /** All per-slot percents (for dashboard grid). */
    fun allLevelPercents(): List<Int> = levelBySlot.map { it * LEVEL_STEP_PERCENT }

    fun nextSlot(): ControlSlot {
        slotIndex = (slotIndex + 1) % slots.size
        return currentSlot()
    }

    fun previousSlot(): ControlSlot {
        slotIndex = if (slotIndex == 0) slots.size - 1 else slotIndex - 1
        return currentSlot()
    }

    /**
     * Axis B: raise level for current slot (clamp).
     * @return Triple(beforePercent, afterPercent, changed)
     */
    fun levelUp(): Triple<Int, Int, Boolean> {
        val before = currentLevelPercent()
        val idx = levelBySlot[slotIndex]
        if (idx < LEVEL_MAX_INDEX) {
            levelBySlot[slotIndex] = idx + 1
        }
        val after = currentLevelPercent()
        return Triple(before, after, after != before)
    }

    /**
     * Axis B: lower level for current slot (clamp).
     */
    fun levelDown(): Triple<Int, Int, Boolean> {
        val before = currentLevelPercent()
        val idx = levelBySlot[slotIndex]
        if (idx > 0) {
            levelBySlot[slotIndex] = idx - 1
        }
        val after = currentLevelPercent()
        return Triple(before, after, after != before)
    }

    /**
     * Set absolute level percent (0..100), snapped to nearest step.
     * Used by VolumeProvider onSetVolumeTo / ABSOLUTE sync.
     * @return Triple(beforePercent, afterPercent, changed)
     */
    fun setLevelPercent(percent: Int): Triple<Int, Int, Boolean> {
        val before = currentLevelPercent()
        val clamped = percent.coerceIn(0, LEVEL_PERCENT_MAX)
        levelBySlot[slotIndex] =
            ((clamped + LEVEL_STEP_PERCENT / 2) / LEVEL_STEP_PERCENT)
                .coerceIn(0, LEVEL_MAX_INDEX)
        val after = currentLevelPercent()
        return Triple(before, after, after != before)
    }

    fun toggleCurrent(): ControlSlot {
        currentSlot().toggle()
        return currentSlot()
    }

    fun brightnessDelta(delta: Int): ControlSlot {
        currentSlot().adjustBrightness(delta)
        return currentSlot()
    }

    fun allSlots(): List<ControlSlot> = slots.toList()

    /** TITLE — slot name/category (current row). */
    fun titleLabel(): String = currentSlot().title

    /**
     * ARTIST — Fit3-readable Korean status for current cell + matrix coords.
     * e.g. `상태: ON | 레벨: 50% | Menu [2/4 × L50]`
     */
    fun artistLabel(): String {
        val slot = currentSlot()
        val level = currentLevelPercent()
        val base = if (slot.supportsBrightness) {
            "상태: ${if (slot.isOn) "ON" else "OFF"} | 밝기: ${slot.brightnessPercent}% | 레벨: $level%"
        } else {
            "상태: ${if (slot.isOn) "ON" else "OFF"} | 레벨: $level%"
        }
        return "$base | Menu [${slotIndex + 1}/${slots.size} × L$level]"
    }

    /** ALBUM — matrix position e.g. `Matrix [2/4 × 50%]`. */
    fun albumLabel(): String =
        "Matrix [${slotIndex + 1}/${slots.size} × ${currentLevelPercent()}%]"

    /** Compact dashboard readout: `행 2/4 × 열 L50 (50%)`. */
    fun positionReadout(): String =
        "행 ${slotIndex + 1}/${slots.size} × 열 L${currentLevelPercent()} (${currentLevelPercent()}%)"

    /**
     * Small text grid for phone dashboard — mark current cell with `>`.
     */
    fun gridText(): String {
        val lines = slots.mapIndexed { i, slot ->
            val marker = if (i == slotIndex) ">" else " "
            val pct = levelBySlot[i] * LEVEL_STEP_PERCENT
            val on = if (slot.isOn) "ON" else "OFF"
            "$marker ${i + 1}. ${slot.name} [$on] L$pct"
        }
        return lines.joinToString("\n")
    }
}
