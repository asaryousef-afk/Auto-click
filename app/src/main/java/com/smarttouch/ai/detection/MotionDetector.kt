package com.smarttouch.ai.detection

import kotlin.math.abs

/**
 * Pure frame-difference motion detector. Works on a small downsampled grid of
 * luminance values (0-255) rather than a raw Bitmap, so this class is fully unit
 * testable on the JVM with no Android dependency. The Android-specific code that
 * turns a screenshot into a luminance grid lives in the accessibility layer.
 *
 * IMPORTANT: this is a general-purpose visual-motion signal, not a perfect video
 * classifier. A moving loading spinner, a live wallpaper, or fast scrolling can also
 * register motion. Sensitivity + confirmation time + no-motion timeout (see
 * [VideoActivityTracker]) are exposed to the user precisely because no single signal
 * can perfectly distinguish "real video" from "other on-screen movement" - the app is
 * built to be tuned, not to pretend it's flawless.
 */
class MotionDetector(
    private var sensitivity: Sensitivity = Sensitivity.MEDIUM,
    private var customThreshold: Float = 0.02f
) {
    private var previousFrame: IntArray? = null

    fun setSensitivity(sensitivity: Sensitivity, customThreshold: Float = this.customThreshold) {
        this.sensitivity = sensitivity
        this.customThreshold = customThreshold
    }

    fun reset() {
        previousFrame = null
    }

    /** Returns a 0f..1f motion score for [currentFrame] compared to the last frame seen. */
    fun analyzeFrame(currentFrame: IntArray): Float {
        val previous = previousFrame
        previousFrame = currentFrame

        if (previous == null || previous.size != currentFrame.size || currentFrame.isEmpty()) {
            return 0f
        }

        var diffSum = 0L
        for (i in currentFrame.indices) {
            diffSum += abs(currentFrame[i] - previous[i])
        }
        return diffSum.toFloat() / (currentFrame.size * 255f)
    }

    fun thresholdForCurrentSensitivity(): Float = thresholdFor(sensitivity, customThreshold)

    fun isMotionSignificant(score: Float): Boolean = score >= thresholdForCurrentSensitivity()

    companion object {
        fun thresholdFor(sensitivity: Sensitivity, customThreshold: Float): Float = when (sensitivity) {
            Sensitivity.LOW -> 0.06f
            Sensitivity.MEDIUM -> 0.03f
            Sensitivity.HIGH -> 0.015f
            Sensitivity.CUSTOM -> customThreshold
        }
    }
}
