package com.poyka.ripdpi.core.testing

import com.poyka.ripdpi.core.ManagedTunnel
import com.poyka.ripdpi.core.TunnelUpstream

open class FakeManagedTunnel(
    var failOnStart: Boolean = false,
) : ManagedTunnel {
    val callLog: MutableList<String> = mutableListOf()

    val startedUpstreams: MutableList<TunnelUpstream> = mutableListOf()

    var startCount: Int = 0
        private set

    var quiesceCount: Int = 0
        private set

    var stopCount: Int = 0
        private set

    private var running: Boolean = false

    override val isRunning: Boolean
        get() = running

    val lastUpstream: TunnelUpstream?
        get() = startedUpstreams.lastOrNull()

    override suspend fun start(upstream: TunnelUpstream) {
        callLog += "start"
        startCount++
        if (failOnStart) {
            error("fake tunnel start failure")
        }
        startedUpstreams += upstream
        running = true
    }

    override suspend fun quiesce() {
        callLog += "quiesce"
        quiesceCount++
        running = false
    }

    override suspend fun stop() {
        callLog += "stop"
        stopCount++
        running = false
    }
}
