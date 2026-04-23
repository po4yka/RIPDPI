package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCapabilityStoreTest {
    @Test
    fun `merge capability record preserves existing flags and updates new evidence`() {
        val existing =
            ServerCapabilityRecord(
                scope = "relay",
                fingerprintHash = "fp",
                authority = "relay.example:443",
                relayProfileId = "primary",
                quicUsable = true,
                udpUsable = true,
                source = "runtime",
                updatedAt = 10L,
            )

        val merged =
            mergeCapabilityRecord(
                existing = existing,
                scope = ServerCapabilityScope.Relay,
                fingerprintHash = "fp",
                authority = "relay.example:443",
                relayProfileId = "primary",
                observation =
                    ServerCapabilityObservation(
                        fallbackRequired = true,
                        repeatedHandshakeFailureClass = "timeout",
                    ),
                source = "test",
                recordedAt = 20L,
            )

        assertTrue(merged.quicUsable == true)
        assertTrue(merged.udpUsable == true)
        assertTrue(merged.fallbackRequired == true)
        assertEquals("timeout", merged.repeatedHandshakeFailureClass)
        assertEquals("test", merged.source)
        assertEquals(20L, merged.updatedAt)
    }

    @Test
    fun `merge capability record derives legacy flags from transport policy envelope`() {
        val merged =
            mergeCapabilityRecord(
                existing = null,
                scope = ServerCapabilityScope.DirectPath,
                fingerprintHash = "fp",
                authority = "example.org:443",
                relayProfileId = null,
                observation =
                    ServerCapabilityObservation(
                        transportPolicy =
                            TransportPolicy(
                                quicMode = QuicMode.HARD_DISABLE,
                                preferredStack = PreferredStack.H2,
                                dnsMode = DnsMode.SYSTEM,
                                tcpFamily = TcpFamily.REC_PRE_SNI,
                                outcome = DirectModeOutcome.NO_DIRECT_SOLUTION,
                            ),
                        ipSetDigest = "deadbeef",
                        transportClass = DirectTransportClass.IP_BLOCK_SUSPECT,
                        reasonCode = DirectModeReasonCode.IP_BLOCKED,
                        cooldownUntil = 500L,
                    ),
                source = "test",
                recordedAt = 20L,
            )

        val envelope = merged.effectiveTransportPolicyEnvelope()

        assertEquals(false, merged.quicUsable)
        assertEquals(false, merged.udpUsable)
        assertEquals(true, merged.fallbackRequired)
        assertEquals("deadbeef", envelope.ipSetDigest)
        assertEquals(DirectTransportClass.IP_BLOCK_SUSPECT, envelope.transportClass)
        assertEquals(DirectModeReasonCode.IP_BLOCKED, envelope.reasonCode)
        assertEquals(500L, envelope.cooldownUntil)
    }

    @Test
    fun `effective transport policy envelope decodes legacy direct path evidence conservatively`() {
        val record =
            ServerCapabilityRecord(
                scope = ServerCapabilityScope.DirectPath.wireValue,
                fingerprintHash = "fp",
                authority = "example.org:443",
                quicUsable = false,
                udpUsable = false,
                fallbackRequired = true,
                repeatedHandshakeFailureClass = "tcp_reset",
                source = "runtime",
                updatedAt = 10L,
            )

        val envelope = record.effectiveTransportPolicyEnvelope()

        assertEquals(QuicMode.HARD_DISABLE, envelope.policy.quicMode)
        assertEquals(PreferredStack.H2, envelope.policy.preferredStack)
        assertEquals(TcpFamily.REC_PRE_SNI, envelope.policy.tcpFamily)
        assertEquals(DirectModeOutcome.TRANSPARENT_OK, envelope.policy.outcome)
        assertEquals(DirectTransportClass.SNI_TLS_SUSPECT, envelope.transportClass)
        assertEquals(DirectModeReasonCode.TCP_POST_CLIENT_HELLO_FAILURE, envelope.reasonCode)
    }
}
