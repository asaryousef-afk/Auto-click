package com.smarttouch.ai.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchIntervalCalculatorTest {

    @Test
    fun `preset interval is used directly`() {
        val result = TouchIntervalCalculator.resolve(selectedIntervalMs = 2000L, customIntervalMs = 9999L)
        assertEquals(2000L, result)
    }

    @Test
    fun `custom sentinel falls back to custom interval`() {
        val result = TouchIntervalCalculator.resolve(
            selectedIntervalMs = TouchIntervalCalculator.CUSTOM_SENTINEL,
            customIntervalMs = 4200L
        )
        assertEquals(4200L, result)
    }

    @Test
    fun `interval below minimum is clamped up`() {
        val result = TouchIntervalCalculator.resolve(selectedIntervalMs = 10L, customIntervalMs = 10L)
        assertEquals(TouchIntervalCalculator.MIN_INTERVAL_MS, result)
    }

    @Test
    fun `custom interval below minimum is also clamped`() {
        val result = TouchIntervalCalculator.resolve(
            selectedIntervalMs = TouchIntervalCalculator.CUSTOM_SENTINEL,
            customIntervalMs = 5L
        )
        assertEquals(TouchIntervalCalculator.MIN_INTERVAL_MS, result)
    }
}
