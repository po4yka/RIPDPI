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
}
