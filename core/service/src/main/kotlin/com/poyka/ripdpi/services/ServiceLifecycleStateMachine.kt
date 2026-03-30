package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger

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
                Logger.d { "Lifecycle: STOPPED -> STARTING" }
                true
            }

            State.STARTING,
            State.RUNNING,
            State.STOPPING,
            -> {
                false
            }
        }

    fun markStarted() {
        Logger.d { "Lifecycle: $state -> RUNNING" }
        state = State.RUNNING
    }

    fun markStartFailed() {
        Logger.d { "Lifecycle: $state -> STOPPED (start failed)" }
        state = State.STOPPED
    }

    fun beginStop() {
        Logger.d { "Lifecycle: $state -> STOPPING" }
        state = State.STOPPING
    }

    fun markStopped() {
        Logger.d { "Lifecycle: $state -> STOPPED" }
        state = State.STOPPED
    }
}
