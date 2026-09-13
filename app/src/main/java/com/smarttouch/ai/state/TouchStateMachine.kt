package com.smarttouch.ai.state

/**
 * Deterministic state machine for the touch engine. Every transition is explicit and
 * logged (visible in Debug Mode) - nothing happens implicitly.
 */
class TouchStateMachine {

    var state: ServiceState = ServiceState.IDLE
        private set

    private val _log = mutableListOf<String>()
    val transitionLog: List<String> get() = _log

    fun start() {
        if (state == ServiceState.IDLE || state == ServiceState.STOPPED || state == ServiceState.PAUSED) {
            transitionTo(ServiceState.VIDEO_DETECTING)
        }
    }

    fun stop() {
        transitionTo(ServiceState.STOPPED)
    }

    fun pause() {
        if (state == ServiceState.VIDEO_DETECTING || state == ServiceState.VIDEO_ACTIVE || state == ServiceState.TOUCHING) {
            transitionTo(ServiceState.PAUSED)
        }
    }

    fun resume() {
        if (state == ServiceState.PAUSED) {
            transitionTo(ServiceState.VIDEO_DETECTING)
        }
    }

    fun onVideoActive() {
        if (state == ServiceState.VIDEO_DETECTING) {
            transitionTo(ServiceState.VIDEO_ACTIVE)
        }
    }

    fun onTouchingStarted() {
        if (state == ServiceState.VIDEO_ACTIVE) {
            transitionTo(ServiceState.TOUCHING)
        }
    }

    fun onVideoInactive() {
        if (state == ServiceState.VIDEO_ACTIVE || state == ServiceState.TOUCHING) {
            transitionTo(ServiceState.VIDEO_DETECTING)
        }
    }

    fun onError() {
        transitionTo(ServiceState.ERROR)
    }

    fun reset() {
        _log.clear()
        state = ServiceState.IDLE
    }

    private fun transitionTo(newState: ServiceState) {
        _log.add("$state -> $newState")
        state = newState
    }
}
