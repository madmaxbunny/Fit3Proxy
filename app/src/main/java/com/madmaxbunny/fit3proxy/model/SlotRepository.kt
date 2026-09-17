package com.madmaxbunny.fit3proxy.model

/**
 * Hardcoded Phase 1 demo slots + 2D [ControlMatrix] (0.4.0).
 * Axis A: Next/Prev → slotIndex. Axis B: Volume ↑/↓ → per-slot level.
 * No persistence / network.
 */
class SlotRepository {

    private val slots: MutableList<ControlSlot> = mutableListOf(
        ControlSlot(
            id = 1,
            category = "조명 제어",
            name = "거실 전등",
            isOn = true,
            brightnessPercent = 75,
            supportsBrightness = true
        ),
        ControlSlot(
            id = 2,
            category = "전원 제어",
            name = "PC 전원",
            isOn = false,
            brightnessPercent = 0,
            supportsBrightness = false
        ),
        ControlSlot(
            id = 3,
            category = "CI/CD",
            name = "배포 파이프라인",
            isOn = false,
            brightnessPercent = 0,
            supportsBrightness = false
        ),
        ControlSlot(
            id = 4,
            category = "조명 제어",
            name = "침실 스탠드",
            isOn = true,
            brightnessPercent = 40,
            supportsBrightness = true
        )
    )

    val matrix: ControlMatrix = ControlMatrix(slots)

    val size: Int get() = matrix.rowCount

    val currentIndexZeroBased: Int get() = matrix.currentSlotIndex

    fun current(): ControlSlot = matrix.currentSlot()

    fun next(): ControlSlot = matrix.nextSlot()

    fun previous(): ControlSlot = matrix.previousSlot()

    fun toggleCurrent(): ControlSlot = matrix.toggleCurrent()

    fun brightnessDelta(delta: Int): ControlSlot = matrix.brightnessDelta(delta)

    fun allSlots(): List<ControlSlot> = matrix.allSlots()
}
