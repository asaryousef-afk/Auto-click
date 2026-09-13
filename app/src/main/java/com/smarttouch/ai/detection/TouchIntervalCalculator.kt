package com.smarttouch.ai.detection

/**
 * Resolves the effective touch interval from the preset options or a custom value.
 * -1L is used as the sentinel for "use the custom interval" throughout the app.
 */
object TouchIntervalCalculator {

    const val CUSTOM_SENTINEL = -1L
    val presetsMs = listOf(500L, 1000L, 2000L, 3000L, 5000L)
    const val MIN_INTERVAL_MS = 100L

    fun resolve(selectedIntervalMs: Long, customIntervalMs: Long): Long {
        val raw = if (selectedIntervalMs == CUSTOM_SENTINEL) customIntervalMs else selectedIntervalMs
        return raw.coerceAtLeast(MIN_INTERVAL_MS)
    }
}
