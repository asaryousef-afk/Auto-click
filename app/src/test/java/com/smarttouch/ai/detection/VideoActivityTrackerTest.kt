package com.smarttouch.ai.detection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoActivityTrackerTest {

    @Test
    fun `single frame of motion does not immediately mark video active`() {
        val tracker = VideoActivityTracker(confirmationTimeMs = 1000, noMotionTimeoutMs = 1500)
        val active = tracker.onFrameAnalyzed(motionDetected = true, nowMs = 0)
        assertFalse(active)
    }

    @Test
    fun `motion sustained past confirmation time marks video active`() {
        val tracker = VideoActivityTracker(confirmationTimeMs = 1000, noMotionTimeoutMs = 1500)
        tracker.onFrameAnalyzed(motionDetected = true, nowMs = 0)
        tracker.onFrameAnalyzed(motionDetected = true, nowMs = 500)
        val active = tracker.onFrameAnalyzed(motionDetected = true, nowMs = 1100)
        assertTrue(active)
    }

    @Test
    fun `single accidental frame change does not trigger activation`() {
        val tracker = VideoActivityTracker(confirmationTimeMs = 1000, noMotionTimeoutMs = 1500)
        tracker.onFrameAnalyzed(motionDetected = true, nowMs = 0)
        val active = tracker.onFrameAnalyzed(motionDetected = false, nowMs = 100)
        assertFalse(active)
    }

    @Test
    fun `stopping motion for longer than timeout deactivates video`() {
        val tracker = VideoActivityTracker(confirmationTimeMs = 200, noMotionTimeoutMs = 1000)
        tracker.onFrameAnalyzed(motionDetected = true, nowMs = 0)
        tracker.onFrameAnalyzed(motionDetected = true, nowMs = 250)
        assertTrue(tracker.videoActive)

        tracker.onFrameAnalyzed(motionDetected = false, nowMs = 300)
        val stillActive = tracker.onFrameAnalyzed(motionDetected = false, nowMs = 600)
        assertTrue("should still be active before timeout elapses", stillActive)

        val nowInactive = tracker.onFrameAnalyzed(motionDetected = false, nowMs = 1400)
        assertFalse("should deactivate after no-motion timeout", nowInactive)
    }

    @Test
    fun `brief motion gap shorter than timeout keeps video active`() {
        val tracker = VideoActivityTracker(confirmationTimeMs = 200, noMotionTimeoutMs = 1000)
        tracker.onFrameAnalyzed(motionDetected = true, nowMs = 0)
        tracker.onFrameAnalyzed(motionDetected = true, nowMs = 250)
        assertTrue(tracker.videoActive)

        tracker.onFrameAnalyzed(motionDetected = false, nowMs = 300)
        val active = tracker.onFrameAnalyzed(motionDetected = true, nowMs = 500)
        assertTrue(active)
    }

    @Test
    fun `reset clears all state`() {
        val tracker = VideoActivityTracker(confirmationTimeMs = 100, noMotionTimeoutMs = 100)
        tracker.onFrameAnalyzed(motionDetected = true, nowMs = 0)
        tracker.onFrameAnalyzed(motionDetected = true, nowMs = 200)
        assertTrue(tracker.videoActive)
        tracker.reset()
        assertFalse(tracker.videoActive)
    }
}
