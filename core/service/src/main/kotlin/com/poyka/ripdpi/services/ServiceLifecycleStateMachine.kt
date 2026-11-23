package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.LogTags

/**
 * The service lifecycle state machine — enforces the
 * `STOPPED → STARTING → RUNNING → STOPPING` transitions and rejects an
 * out-of-order start. Service start/stop behavior is frozen.
 */
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
                Logger.d(tag = LogTags.SERVICE) { "Lifecycle: STOPPED -> STARTING" }
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
        Logger.d(tag = LogTags.SERVICE) { "Lifecycle: $state -> RUNNING" }
        state = State.RUNNING
    }

    fun markStartFailed() {
        Logger.d(tag = LogTags.SERVICE) { "Lifecycle: $state -> STOPPED (start failed)" }
        state = State.STOPPED
    }

    fun beginStop() {
        Logger.d(tag = LogTags.SERVICE) { "Lifecycle: $state -> STOPPING" }
        state = State.STOPPING
    }

    fun markStopped() {
        Logger.d(tag = LogTags.SERVICE) { "Lifecycle: $state -> STOPPED" }
        state = State.STOPPED
    }
}
