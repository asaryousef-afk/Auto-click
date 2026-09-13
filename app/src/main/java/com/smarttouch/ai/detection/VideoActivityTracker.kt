package com.smarttouch.ai.detection

/**
 * Turns a stream of per-frame motion booleans into a debounced VIDEO_ACTIVE signal:
 * - motion must be sustained for [confirmationTimeMs] before we call it "active"
 *   (so one accidental frame change doesn't trigger touching)
 * - once active, motion must be absent for [noMotionTimeoutMs] before we call it
 *   "inactive" again (so a single dropped/duplicate frame doesn't stop touching)
 */
class VideoActivityTracker(
    private var confirmationTimeMs: Long,
    private var noMotionTimeoutMs: Long
) {
    private var motionStartedAtMs: Long? = null
    private var lastMotionAtMs: Long? = null

    var videoActive: Boolean = false
        private set

    fun updateTimings(confirmationTimeMs: Long, noMotionTimeoutMs: Long) {
        this.confirmationTimeMs = confirmationTimeMs
        this.noMotionTimeoutMs = noMotionTimeoutMs
    }

    /** Feed one frame's motion result. Returns the (possibly updated) videoActive state. */
    fun onFrameAnalyzed(motionDetected: Boolean, nowMs: Long): Boolean {
        if (motionDetected) {
            lastMotionAtMs = nowMs
            if (motionStartedAtMs == null) {
                motionStartedAtMs = nowMs
            }
            val sustainedMs = nowMs - (motionStartedAtMs ?: nowMs)
            if (!videoActive && sustainedMs >= confirmationTimeMs) {
                videoActive = true
            }
        } else {
            motionStartedAtMs = null
            val last = lastMotionAtMs
            if (videoActive) {
                if (last == null || nowMs - last >= noMotionTimeoutMs) {
                    videoActive = false
                }
            }
        }
        return videoActive
    }

    fun reset() {
        motionStartedAtMs = null
        lastMotionAtMs = null
        videoActive = false
    }
}
