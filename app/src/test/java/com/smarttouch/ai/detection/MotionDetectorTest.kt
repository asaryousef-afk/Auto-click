package com.smarttouch.ai.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class MotionDetectorTest {

    @Test
    fun `first frame produces zero motion score`() {
        val detector = MotionDetector(Sensitivity.MEDIUM)
        val frame = IntArray(16) { 100 }
        val score = detector.analyzeFrame(frame)
        assertEquals(0f, score, 0.0001f)
    }

    @Test
    fun `identical consecutive frames produce zero motion`() {
        val detector = MotionDetector(Sensitivity.MEDIUM)
        val frame = IntArray(16) { 120 }
        detector.analyzeFrame(frame)
        val score = detector.analyzeFrame(frame.copyOf())
        assertEquals(0f, score, 0.0001f)
    }

    @Test
    fun `very different consecutive frames produce high motion score`() {
        val detector = MotionDetector(Sensitivity.MEDIUM)
        val dark = IntArray(16) { 0 }
        val bright = IntArray(16) { 255 }
        detector.analyzeFrame(dark)
        val score = detector.analyzeFrame(bright)
        assertEquals(1f, score, 0.01f)
    }

    @Test
    fun `higher sensitivity uses lower threshold`() {
        val low = MotionDetector.thresholdFor(Sensitivity.LOW, 0.02f)
        val medium = MotionDetector.thresholdFor(Sensitivity.MEDIUM, 0.02f)
        val high = MotionDetector.thresholdFor(Sensitivity.HIGH, 0.02f)
        assertTrue(high < medium)
        assertTrue(medium < low)
    }

    @Test
    fun `custom sensitivity uses the provided threshold`() {
        val threshold = MotionDetector.thresholdFor(Sensitivity.CUSTOM, 0.077f)
        assertEquals(0.077f, threshold, 0.0001f)
    }

    @Test
    fun `isMotionSignificant respects configured sensitivity`() {
        val detector = MotionDetector(Sensitivity.HIGH)
        val small = IntArray(100) { 100 }
        val slightlyDifferent = IntArray(100) { 104 }
        detector.analyzeFrame(small)
        val score = detector.analyzeFrame(slightlyDifferent)
        // small change should register as significant under HIGH sensitivity
        assertTrue(detector.isMotionSignificant(score))
    }

    @Test
    fun `low sensitivity ignores tiny changes that high sensitivity would catch`() {
        val detector = MotionDetector(Sensitivity.LOW)
        val small = IntArray(100) { 100 }
        val slightlyDifferent = IntArray(100) { 104 }
        detector.analyzeFrame(small)
        val score = detector.analyzeFrame(slightlyDifferent)
        assertFalse(detector.isMotionSignificant(score))
    }
}
