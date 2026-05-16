package com.poyka.ripdpi.services.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectHealthGateTest {
    @Test
    fun `initial state is Connected`() {
        val gate = ReconnectHealthGate()

        assertEquals(ReconnectGateState.Connected, gate.state)
    }

    @Test
    fun `wifi to lte transition enters Reconnecting`() {
        val gate = ReconnectHealthGate()

        gate.onNetworkTransition(from = "wifi", to = "cellular")

        assertEquals(ReconnectGateState.Reconnecting, gate.state)
    }

    @Test
    fun `lte to wifi transition enters Reconnecting`() {
        val gate = ReconnectHealthGate()

        gate.onNetworkTransition(from = "cellular", to = "wifi")

        assertEquals(ReconnectGateState.Reconnecting, gate.state)
    }

    @Test
    fun `sleep to wake transition enters Reconnecting`() {
        val gate = ReconnectHealthGate()

        gate.onSleepWake()

        assertEquals(ReconnectGateState.Reconnecting, gate.state)
    }

    @Test
    fun `captive to post-captive transition enters Reconnecting`() {
        val gate = ReconnectHealthGate()

        gate.onCaptivePortalCleared()

        assertEquals(ReconnectGateState.Reconnecting, gate.state)
    }

    @Test
    fun `HealthOk ack from Reconnecting transitions to Connected`() {
        val gate = ReconnectHealthGate()
        gate.onNetworkTransition(from = "wifi", to = "cellular")
        assertEquals(ReconnectGateState.Reconnecting, gate.state)

        gate.onHealthOk()

        assertEquals(ReconnectGateState.Connected, gate.state)
    }

    @Test
    fun `tunnel is not connected during Reconnecting`() {
        val gate = ReconnectHealthGate()
        gate.onNetworkTransition(from = "wifi", to = "cellular")

        assertFalse(gate.isTunnelConnected())
    }

    @Test
    fun `tunnel is connected only after HealthOk`() {
        val gate = ReconnectHealthGate()
        gate.onNetworkTransition(from = "wifi", to = "cellular")
        assertFalse(gate.isTunnelConnected())

        gate.onHealthOk()

        assertTrue(gate.isTunnelConnected())
    }

    @Test
    fun `HealthOk while Connected has no effect`() {
        val gate = ReconnectHealthGate()
        assertEquals(ReconnectGateState.Connected, gate.state)

        gate.onHealthOk()

        assertEquals(ReconnectGateState.Connected, gate.state)
        assertTrue(gate.isTunnelConnected())
    }
}
