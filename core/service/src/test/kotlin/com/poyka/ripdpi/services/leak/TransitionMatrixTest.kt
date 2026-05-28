package com.poyka.ripdpi.services.leak

import com.poyka.ripdpi.services.network.ReconnectGateState
import com.poyka.ripdpi.services.network.ReconnectHealthGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transition matrix: for each of Wi-Fi→LTE, LTE→Wi-Fi, sleep→wake, captive→post-captive,
 * the tunnel must not transition to Connected until health checks pass.
 *
 * Reuses [ReconnectHealthGate] without duplicating the reconnect gate logic.
 */
class TransitionMatrixTest {
    @Test
    fun wifiToLteBlocksConnectedUntilHealthOk() {
        val gate = ReconnectHealthGate()
        gate.onNetworkTransition(from = "wifi", to = "cellular")
        assertEquals(ReconnectGateState.Reconnecting, gate.state)
        assertFalse(gate.isTunnelConnected())
        gate.onHealthOk()
        assertTrue(gate.isTunnelConnected())
    }

    @Test
    fun lteToWifiBlocksConnectedUntilHealthOk() {
        val gate = ReconnectHealthGate()
        gate.onNetworkTransition(from = "cellular", to = "wifi")
        assertEquals(ReconnectGateState.Reconnecting, gate.state)
        assertFalse(gate.isTunnelConnected())
        gate.onHealthOk()
        assertTrue(gate.isTunnelConnected())
    }

    @Test
    fun sleepWakeBlocksConnectedUntilHealthOk() {
        val gate = ReconnectHealthGate()
        gate.onSleepWake()
        assertEquals(ReconnectGateState.Reconnecting, gate.state)
        assertFalse(gate.isTunnelConnected())
        gate.onHealthOk()
        assertTrue(gate.isTunnelConnected())
    }

    @Test
    fun captivePortalClearanceBlocksConnectedUntilHealthOk() {
        val gate = ReconnectHealthGate()
        gate.onCaptivePortalCleared()
        assertEquals(ReconnectGateState.Reconnecting, gate.state)
        assertFalse(gate.isTunnelConnected())
        gate.onHealthOk()
        assertTrue(gate.isTunnelConnected())
    }

    @Test
    fun sameTransportKeyDoesNotTriggerReconnecting() {
        val gate = ReconnectHealthGate()
        gate.onNetworkTransition(from = "wifi", to = "wifi")
        assertEquals(ReconnectGateState.Connected, gate.state)
    }

    @Test
    fun sequentialTransitionsRequireSingleHealthOkToResolve() {
        val gate = ReconnectHealthGate()
        gate.onNetworkTransition(from = "wifi", to = "cellular")
        gate.onSleepWake()
        assertEquals(ReconnectGateState.Reconnecting, gate.state)
        gate.onHealthOk()
        assertEquals(ReconnectGateState.Connected, gate.state)
    }

    @Test
    fun healthOkWhileAlreadyConnectedIsNoOp() {
        val gate = ReconnectHealthGate()
        assertTrue(gate.isTunnelConnected())
        gate.onHealthOk()
        assertEquals(ReconnectGateState.Connected, gate.state)
    }
}
