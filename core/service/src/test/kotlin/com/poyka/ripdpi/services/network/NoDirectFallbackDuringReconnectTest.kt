package com.poyka.ripdpi.services.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoDirectFallbackDuringReconnectTest {
    @Test
    fun `direct fallback is allowed when Connected`() {
        val gate = ReconnectHealthGate()
        assertEquals(ReconnectGateState.Connected, gate.state)

        val result = gate.requestDirectFallback()

        assertEquals(DirectFallbackResult.Allowed, result)
    }

    @Test
    fun `direct fallback is blocked during Reconnecting`() {
        val gate = ReconnectHealthGate()
        gate.onNetworkTransition(from = "wifi", to = "cellular")
        assertEquals(ReconnectGateState.Reconnecting, gate.state)

        val result = gate.requestDirectFallback()

        assertEquals(DirectFallbackResult.Blocked, result)
    }

    @Test
    fun `direct fallback becomes allowed again after HealthOk`() {
        val gate = ReconnectHealthGate()
        gate.onNetworkTransition(from = "wifi", to = "cellular")
        assertEquals(DirectFallbackResult.Blocked, gate.requestDirectFallback())

        gate.onHealthOk()

        assertEquals(DirectFallbackResult.Allowed, gate.requestDirectFallback())
    }

    @Test
    fun `wifi lte wifi sequence blocks then unblocks fallback`() {
        val gate = ReconnectHealthGate()

        gate.onNetworkTransition(from = "wifi", to = "cellular")
        assertFalse(gate.requestDirectFallback() == DirectFallbackResult.Allowed)

        gate.onHealthOk()
        assertTrue(gate.requestDirectFallback() == DirectFallbackResult.Allowed)

        gate.onNetworkTransition(from = "cellular", to = "wifi")
        assertFalse(gate.requestDirectFallback() == DirectFallbackResult.Allowed)

        gate.onHealthOk()
        assertTrue(gate.requestDirectFallback() == DirectFallbackResult.Allowed)
    }
}
