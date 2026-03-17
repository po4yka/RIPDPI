package com.poyka.ripdpi.services

import logcat.LogPriority
import logcat.logcat

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
                logcat(LogPriority.DEBUG) { "Lifecycle: STOPPED -> STARTING" }
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
        logcat(LogPriority.DEBUG) { "Lifecycle: $state -> RUNNING" }
        state = State.RUNNING
    }

    fun markStartFailed() {
        if (state == State.STARTING) {
            logcat(LogPriority.DEBUG) { "Lifecycle: STARTING -> STOPPED (start failed)" }
            state = State.STOPPED
        }
    }

    fun beginStop() {
        logcat(LogPriority.DEBUG) { "Lifecycle: $state -> STOPPING" }
        state = State.STOPPING
    }

    fun markStopped() {
        logcat(LogPriority.DEBUG) { "Lifecycle: $state -> STOPPED" }
        state = State.STOPPED
    }
}
