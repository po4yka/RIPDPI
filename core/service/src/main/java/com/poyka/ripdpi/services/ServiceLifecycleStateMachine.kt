package com.poyka.ripdpi.services

internal class ServiceLifecycleStateMachine {
    enum class State {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
    }

    var state: State = State.STOPPED
        private set

    fun tryBeginStart(): Boolean =
        when (state) {
            State.STOPPED -> {
                state = State.STARTING
                true
            }

            State.STARTING,
            State.RUNNING,
            State.STOPPING,
            -> false
        }

    fun markStarted() {
        state = State.RUNNING
    }

    fun markStartFailed() {
        if (state == State.STARTING) {
            state = State.STOPPED
        }
    }

    fun beginStop() {
        state = State.STOPPING
    }

    fun markStopped() {
        state = State.STOPPED
    }
}
