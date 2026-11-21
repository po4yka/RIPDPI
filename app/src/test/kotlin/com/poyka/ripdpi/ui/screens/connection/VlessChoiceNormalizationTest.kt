package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.data.RelayKindVless
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayVlessFlowVision
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
import com.poyka.ripdpi.data.RelayXhttpModeAuto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlessChoiceNormalizationTest {
    private val realityTcpVision =
        ConnectionDraft(
            relayEnabled = true,
            relayKind = RelayKindVlessReality,
            relayVlessTransport = RelayVlessTransportRealityTcp,
            relayVlessFlow = RelayVlessFlowVision,
            relayUdpEnabled = true,
        )

    @Test
    fun `switching REALITY TCP with Vision to xHTTP clears the flow the transport rejects`() {
        val next = realityTcpVision.copy(relayVlessTransport = RelayVlessTransportXhttp).withValidVlessChoices()

        assertEquals("", next.selectedVlessFlow())
        // xHTTP with the empty flow carries UDP, so the toggle stays.
        assertTrue(next.relayUdpEnabled)
    }

    @Test
    fun `turning Vision off on REALITY TCP turns UDP off`() {
        val next = realityTcpVision.copy(relayVlessFlow = "").withValidVlessChoices()

        assertFalse(next.relayUdpEnabled)
    }

    @Test
    fun `switching to plain VLESS moves the transport to xHTTP and drops an unknown mode`() {
        val next =
            realityTcpVision
                .copy(relayKind = RelayKindVless, relayXhttpMode = "legacy")
                .withValidVlessChoices()

        assertEquals(RelayVlessTransportXhttp, next.relayVlessTransport)
        assertEquals("", next.selectedVlessFlow())
        assertEquals(RelayXhttpModeAuto, next.relayXhttpMode)
    }

    @Test
    fun `valid choices stay as they are`() {
        assertEquals(realityTcpVision, realityTcpVision.withValidVlessChoices())
    }
}
