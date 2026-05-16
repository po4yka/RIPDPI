package com.poyka.ripdpi.services.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * onRevoke() closes TUN fd, sockets, provider runtime, local inbounds in
 * deterministic order; idempotent on double-call.
 */
class OnRevokeCleanupTest {
    private fun buildFlow(calls: MutableList<String>): OnRevokeFlow =
        OnRevokeFlow(
            closeTunFd = { calls += "tun" },
            closeTunnelSockets = { calls += "socket" },
            stopProviderRuntime = { calls += "runtime" },
            stopLocalInbounds = { calls += "inbound" },
        )

    @Test
    fun tunFdClosedFirst() {
        val calls = mutableListOf<String>()
        buildFlow(calls).execute()
        assertEquals("tun", calls[0])
    }

    @Test
    fun tunnelSocketsClosedSecond() {
        val calls = mutableListOf<String>()
        buildFlow(calls).execute()
        assertEquals("socket", calls[1])
    }

    @Test
    fun providerRuntimeStoppedThird() {
        val calls = mutableListOf<String>()
        buildFlow(calls).execute()
        assertEquals("runtime", calls[2])
    }

    @Test
    fun localInboundsStoppedFourth() {
        val calls = mutableListOf<String>()
        buildFlow(calls).execute()
        assertEquals("inbound", calls[3])
    }

    @Test
    fun fullOrderIsTunSocketRuntimeInbound() {
        val calls = mutableListOf<String>()
        buildFlow(calls).execute()
        assertEquals(listOf("tun", "socket", "runtime", "inbound"), calls)
    }

    @Test
    fun doubleCallIsIdempotent() {
        val calls = mutableListOf<String>()
        val flow = buildFlow(calls)
        flow.execute()
        flow.execute()
        assertEquals("Double-call must not duplicate steps", 4, calls.size)
    }

    @Test
    fun wasExecutedFalseBeforeCall() {
        val flow = buildFlow(mutableListOf())
        assertFalse(flow.wasExecuted())
    }

    @Test
    fun wasExecutedTrueAfterCall() {
        val flow = buildFlow(mutableListOf())
        flow.execute()
        assertTrue(flow.wasExecuted())
    }

    @Test
    fun allFourResourcesClosedOnSingleCall() {
        var tunClosed = false
        var socketClosed = false
        var runtimeStopped = false
        var inboundStopped = false
        val flow =
            OnRevokeFlow(
                closeTunFd = { tunClosed = true },
                closeTunnelSockets = { socketClosed = true },
                stopProviderRuntime = { runtimeStopped = true },
                stopLocalInbounds = { inboundStopped = true },
            )
        flow.execute()
        assertTrue(tunClosed)
        assertTrue(socketClosed)
        assertTrue(runtimeStopped)
        assertTrue(inboundStopped)
    }
}
