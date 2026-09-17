package com.madmaxbunny.fit3proxy.model

/**
 * Hardcoded Phase 1 demo slots (SOW examples). No persistence / network.
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

    private var currentIndex: Int = 0

    val size: Int get() = slots.size

    val currentIndexZeroBased: Int get() = currentIndex

    fun current(): ControlSlot = slots[currentIndex]

    fun next(): ControlSlot {
        currentIndex = (currentIndex + 1) % slots.size
        return current()
    }

    fun previous(): ControlSlot {
        currentIndex = if (currentIndex == 0) slots.size - 1 else currentIndex - 1
        return current()
    }

    fun toggleCurrent(): ControlSlot {
        current().toggle()
        return current()
    }

    fun brightnessDelta(delta: Int): ControlSlot {
        current().adjustBrightness(delta)
        return current()
    }

    fun allSlots(): List<ControlSlot> = slots.toList()
}
