package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayKindOff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptanceTransportProbePlanTest {
    @Test
    fun `every shipped relay kind derives probe applicability from its descriptor`() {
        RelayKindDescriptors.forEach { descriptor ->
            val plan = acceptanceTransportProbePlan(descriptor.kindId)

            if (descriptor.kindId == RelayKindOff) {
                assertEquals(AcceptanceProbeApplicability.Unavailable, plan.relayTcp)
                assertEquals(AcceptanceProbeApplicability.Unavailable, plan.relayUdp)
                assertFalse(plan.requiresRelayListener)
            } else {
                assertEquals(descriptor.tcp.expectedApplicability(), plan.relayTcp)
                assertEquals(descriptor.udp.expectedApplicability(), plan.relayUdp)
                assertEquals(descriptor.udp.expectedApplicability(), plan.relayUdpPayload)
                assertEquals(AcceptanceProbeApplicability.NotApplicable, plan.awgRuntime)
                assertTrue(plan.requiresRelayListener)
            }
        }
    }

    @Test
    fun `unknown relay context is unavailable rather than passed`() {
        val plan = acceptanceTransportProbePlan("unknown")

        assertEquals(AcceptanceProbeApplicability.Unavailable, plan.relayTcp)
        assertEquals(AcceptanceProbeApplicability.Unavailable, plan.relayUdp)
        assertEquals(AcceptanceProbeApplicability.Unavailable, plan.relayUdpPayload)
        assertEquals(AcceptanceProbeApplicability.Unavailable, plan.awgRuntime)
        assertFalse(plan.requiresRelayListener)
    }

    @Test
    fun `published AmneziaWG runtime never borrows relay SOCKS applicability`() {
        val plan = acceptanceTransportProbePlan(RelayKindOff, awgSelected = true)

        assertEquals("amneziawg", plan.transportKind)
        assertEquals(AcceptanceProbeApplicability.NotApplicable, plan.relayTcp)
        assertEquals(AcceptanceProbeApplicability.NotApplicable, plan.relayUdp)
        assertEquals(AcceptanceProbeApplicability.NotApplicable, plan.relayUdpPayload)
        assertEquals(AcceptanceProbeApplicability.Required, plan.awgRuntime)
        assertFalse(plan.requiresRelayListener)
    }

    @Test
    fun `unknown selected egress makes stale AWG telemetry inconclusive`() {
        val plan = acceptanceTransportProbePlan("vless_reality", awgSelected = null)

        assertEquals(AcceptanceProbeApplicability.Unavailable, plan.relayTcp)
        assertEquals(AcceptanceProbeApplicability.Unavailable, plan.awgRuntime)
        assertFalse(plan.requiresRelayListener)
    }

    private fun Boolean.expectedApplicability(): AcceptanceProbeApplicability =
        if (this) AcceptanceProbeApplicability.Required else AcceptanceProbeApplicability.NotApplicable
}
