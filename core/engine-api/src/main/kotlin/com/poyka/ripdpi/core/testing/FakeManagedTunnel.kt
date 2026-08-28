package com.poyka.ripdpi.core.testing

import com.poyka.ripdpi.core.ManagedTunnel
import com.poyka.ripdpi.core.TunnelUpstream

/**
 * Deterministic in-memory [ManagedTunnel] for orchestrator unit tests.
 *
 * Records every upstream it was started with and the ordered call log so tests
 * can assert provider selection (which endpoint the tunnel was pointed at) and
 * handover ordering (stop-before-restart). Set [failOnStart] to simulate a
 * tunnel-start failure and exercise the orchestrator's teardown path.
 */
open class FakeManagedTunnel(
    var failOnStart: Boolean = false,
) : ManagedTunnel {
    /** Ordered log of method names ("start" / "stop"). */
    val callLog: MutableList<String> = mutableListOf()

    /** Every upstream passed to [start], in order. */
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

    /** The most recent upstream the tunnel was pointed at, or null. */
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
