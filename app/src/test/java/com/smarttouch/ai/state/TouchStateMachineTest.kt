package com.smarttouch.ai.state

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchStateMachineTest {

    @Test
    fun `initial state is IDLE`() {
        val sm = TouchStateMachine()
        assertEquals(ServiceState.IDLE, sm.state)
    }

    @Test
    fun `start moves from IDLE to VIDEO_DETECTING`() {
        val sm = TouchStateMachine()
        sm.start()
        assertEquals(ServiceState.VIDEO_DETECTING, sm.state)
    }

    @Test
    fun `full happy path reaches TOUCHING`() {
        val sm = TouchStateMachine()
        sm.start()
        sm.onVideoActive()
        sm.onTouchingStarted()
        assertEquals(ServiceState.TOUCHING, sm.state)
    }

    @Test
    fun `video going inactive returns to VIDEO_DETECTING from TOUCHING`() {
        val sm = TouchStateMachine()
        sm.start()
        sm.onVideoActive()
        sm.onTouchingStarted()
        sm.onVideoInactive()
        assertEquals(ServiceState.VIDEO_DETECTING, sm.state)
    }

    @Test
    fun `stop always moves to STOPPED`() {
        val sm = TouchStateMachine()
        sm.start()
        sm.onVideoActive()
        sm.onTouchingStarted()
        sm.stop()
        assertEquals(ServiceState.STOPPED, sm.state)
    }

    @Test
    fun `pause from TOUCHING then resume returns to VIDEO_DETECTING`() {
        val sm = TouchStateMachine()
        sm.start()
        sm.onVideoActive()
        sm.onTouchingStarted()
        sm.pause()
        assertEquals(ServiceState.PAUSED, sm.state)
        sm.resume()
        assertEquals(ServiceState.VIDEO_DETECTING, sm.state)
    }

    @Test
    fun `pause is ignored when already stopped`() {
        val sm = TouchStateMachine()
        sm.stop()
        sm.pause()
        assertEquals(ServiceState.STOPPED, sm.state)
    }

    @Test
    fun `onTouchingStarted is ignored unless video is active`() {
        val sm = TouchStateMachine()
        sm.start()
        sm.onTouchingStarted() // no onVideoActive() call first
        assertEquals(ServiceState.VIDEO_DETECTING, sm.state)
    }

    @Test
    fun `error transition is always possible`() {
        val sm = TouchStateMachine()
        sm.onError()
        assertEquals(ServiceState.ERROR, sm.state)
    }

    @Test
    fun `transitions are logged`() {
        val sm = TouchStateMachine()
        sm.start()
        sm.onVideoActive()
        assertEquals(2, sm.transitionLog.size)
    }
}
