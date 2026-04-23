package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeRuntimeSnapshotTest {
    @Test
    fun `idle snapshot has default values`() {
        val snapshot = NativeRuntimeSnapshot.idle("proxy")
        assertEquals("proxy", snapshot.source)
        assertEquals("idle", snapshot.state)
        assertEquals("idle", snapshot.health)
        assertEquals(0L, snapshot.activeSessions)
        assertEquals(0L, snapshot.totalSessions)
        assertEquals(0L, snapshot.totalErrors)
        assertEquals(0, snapshot.blockedHostCount)
        assertEquals(null, snapshot.lastBlockSignal)
        assertEquals(null, snapshot.lastBlockProvider)
        assertEquals(null, snapshot.lastAutolearnAction)
    }

    @Test
    fun `TunnelStats fromNative parses full array`() {
        val stats = TunnelStats.fromNative(longArrayOf(100, 2000, 50, 1000))
        assertEquals(100L, stats.txPackets)
        assertEquals(2000L, stats.txBytes)
        assertEquals(50L, stats.rxPackets)
        assertEquals(1000L, stats.rxBytes)
    }

    @Test
    fun `TunnelStats fromNative handles empty array`() {
        val stats = TunnelStats.fromNative(longArrayOf())
        assertEquals(0L, stats.txPackets)
        assertEquals(0L, stats.txBytes)
        assertEquals(0L, stats.rxPackets)
        assertEquals(0L, stats.rxBytes)
    }

    @Test
    fun `TunnelStats fromNative handles partial array`() {
        val stats = TunnelStats.fromNative(longArrayOf(10, 200))
        assertEquals(10L, stats.txPackets)
        assertEquals(200L, stats.txBytes)
        assertEquals(0L, stats.rxPackets)
        assertEquals(0L, stats.rxBytes)
    }

    @Test
    fun `TunnelStats default values are all zero`() {
        val stats = TunnelStats()
        assertEquals(0L, stats.txPackets)
        assertEquals(0L, stats.txBytes)
        assertEquals(0L, stats.rxPackets)
        assertEquals(0L, stats.rxBytes)
    }

    @Test
    fun `direct path learning signals round trip through snapshot json`() {
        val snapshot =
            NativeRuntimeSnapshot(
                source = "proxy",
                state = "running",
                directPathLearningSignals =
                    listOf(
                        DirectPathLearningSignal(
                            authority = "example.org:443",
                            ipSetDigest = "deadbeef",
                            event = DirectPathLearningEvent.TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK,
                            strategyFamily = "tlsrec_disorder",
                            capturedAt = 123L,
                        ),
                        DirectPathLearningSignal(
                            authority = "example.net:443",
                            ipSetDigest = "",
                            event = DirectPathLearningEvent.QUIC_SUCCESS,
                            capturedAt = 456L,
                        ),
                    ),
            )

        val encoded = snapshotJson.encodeToString(NativeRuntimeSnapshot.serializer(), snapshot)
        val decoded = snapshotJson.decodeFromString(NativeRuntimeSnapshot.serializer(), encoded)

        assertEquals(2, decoded.directPathLearningSignals.size)
        assertEquals(
            DirectPathLearningEvent.TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK,
            decoded.directPathLearningSignals.first().event,
        )
        assertEquals("tlsrec_disorder", decoded.directPathLearningSignals.first().strategyFamily)
        assertEquals(456L, decoded.directPathLearningSignals.last().capturedAt)
        assertNull(decoded.directPathLearningSignals.last().strategyFamily)
    }

    private companion object {
        val snapshotJson =
            kotlinx.serialization.json.Json {
                explicitNulls = true
                encodeDefaults = true
            }
    }
}
