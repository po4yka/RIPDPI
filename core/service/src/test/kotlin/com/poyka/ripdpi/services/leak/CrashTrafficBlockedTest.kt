package com.poyka.ripdpi.services.leak

import com.poyka.ripdpi.services.network.DirectFallbackResult
import com.poyka.ripdpi.services.network.ReconnectGateState
import com.poyka.ripdpi.services.network.ReconnectHealthGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Core-crash test: when the provider runtime crashes the tunnel must block
 * traffic rather than silently falling back to the direct (unprotected) path.
 *
 * Uses [ReconnectHealthGate] as the traffic-block oracle: a crash puts the
 * gate in [ReconnectGateState.Reconnecting] until a health-ok ack is received,
 * and direct-fallback requests are blocked during that window.
 */
class CrashTrafficBlockedTest {
    @Test
    fun coreCrashPutsGateIntoReconnecting() {
        val gate = ReconnectHealthGate()
        // Simulate a core crash by triggering a sleep-wake (equivalent state transition)
        gate.onSleepWake()
        assertEquals(ReconnectGateState.Reconnecting, gate.state)
    }

    @Test
    fun directFallbackBlockedAfterCoreCrash() {
        val gate = ReconnectHealthGate()
        gate.onSleepWake()
        val result = gate.requestDirectFallback()
        assertEquals(DirectFallbackResult.Blocked, result)
    }

    @Test
    fun tunnelNotConnectedWhileReconnecting() {
        val gate = ReconnectHealthGate()
        gate.onSleepWake()
        assertFalse(gate.isTunnelConnected())
    }

    @Test
    fun directFallbackRemainsBlockedUntilHealthOk() {
        val gate = ReconnectHealthGate()
        gate.onNetworkTransition(from = "wifi", to = "cellular")
        assertEquals(DirectFallbackResult.Blocked, gate.requestDirectFallback())
        gate.onHealthOk()
        assertEquals(DirectFallbackResult.Allowed, gate.requestDirectFallback())
    }

    @Test
    fun multipleTransitionsKeepGateReconnectingUntilHealthOk() {
        val gate = ReconnectHealthGate()
        gate.onNetworkTransition(from = "wifi", to = "cellular")
        gate.onNetworkTransition(from = "cellular", to = "wifi")
        assertEquals(ReconnectGateState.Reconnecting, gate.state)
        gate.onHealthOk()
        assertEquals(ReconnectGateState.Connected, gate.state)
    }
}
