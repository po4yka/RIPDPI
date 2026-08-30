package com.poyka.ripdpi.data

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRuntimeSnapshotTest {
    @Test
    fun `owned stack required runtime event is recognized`() {
        assertTrue(DirectPathLearningEvent("OWNED_STACK_REQUIRED").isKnown)
    }

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

    @Test
    fun `unknown top-level snapshot field is tolerated by the forward-compatible parser`() {
        // Mirrors the production engine telemetry decoders, which all configure
        // Json { ignoreUnknownKeys = true } — see TELEMETRY_CONTRACT.md. A future
        // Rust build that adds a snapshot field must not break an older app build.
        val json =
            """
            { "source": "proxy", "schemaVersion": 3, "state": "running", "futureUnknownSnapshotField": 42 }
            """.trimIndent()

        val decoded = forwardCompatJson.decodeFromString(NativeRuntimeSnapshot.serializer(), json)

        assertEquals("proxy", decoded.source)
        assertEquals("running", decoded.state)
    }

    @Test
    fun `unknown field inside a native event is tolerated`() {
        val json =
            """
            {
              "source": "proxy",
              "schemaVersion": 3,
              "nativeEvents": [
                { "source": "proxy", "level": "info", "message": "ready",
                  "createdAt": 1, "futureEventField": "x" }
              ]
            }
            """.trimIndent()

        val decoded = forwardCompatJson.decodeFromString(NativeRuntimeSnapshot.serializer(), json)

        assertEquals(1, decoded.nativeEvents.size)
        assertEquals("ready", decoded.nativeEvents.first().message)
    }

    @Test
    fun `unknown future event kind string decodes verbatim`() {
        // NativeRuntimeEvent.kind is an open String? — a new event kind emitted
        // by a future Rust build decodes without an enum to reject it.
        val json =
            """
            {
              "source": "proxy",
              "schemaVersion": 3,
              "nativeEvents": [
                { "source": "proxy", "level": "info", "message": "m",
                  "createdAt": 2, "kind": "future_event_kind_v2" }
              ]
            }
            """.trimIndent()

        val decoded = forwardCompatJson.decodeFromString(NativeRuntimeSnapshot.serializer(), json)

        assertEquals("future_event_kind_v2", decoded.nativeEvents.first().kind)
    }

    @Test
    fun `unknown direct path learning event decodes verbatim instead of failing the snapshot`() {
        // DirectPathLearningEvent is a wire-preserving value class, not an enum:
        // a future runtime emitting a new event name must not break decoding of
        // the entire enclosing NativeRuntimeSnapshot for an older app build.
        val json =
            """
            {
              "source": "proxy",
              "schemaVersion": 3,
              "directPathLearningSignals": [
                { "authority": "example.org:443", "ipSetDigest": "deadbeef",
                  "event": "FUTURE_DIRECT_PATH_EVENT_V2", "capturedAt": 99 }
              ]
            }
            """.trimIndent()

        val decoded = forwardCompatJson.decodeFromString(NativeRuntimeSnapshot.serializer(), json)

        val signal = decoded.directPathLearningSignals.single()
        assertEquals("FUTURE_DIRECT_PATH_EVENT_V2", signal.event.wire)
        assertFalse(signal.event.isKnown)
    }

    @Test
    fun `known direct path learning events report isKnown with their exact wire strings`() {
        val knownByWire =
            mapOf(
                "QUIC_SUCCESS" to DirectPathLearningEvent.QUIC_SUCCESS,
                "QUIC_BLOCKED_TCP_OK" to DirectPathLearningEvent.QUIC_BLOCKED_TCP_OK,
                "TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK" to DirectPathLearningEvent.TCP_POST_CLIENT_HELLO_FAILURE_TCP_OK,
                "ALL_IPS_FAILED" to DirectPathLearningEvent.ALL_IPS_FAILED,
                "NO_TCP_FALLBACK_DETECTED" to DirectPathLearningEvent.NO_TCP_FALLBACK_DETECTED,
                "OWNED_STACK_REQUIRED" to DirectPathLearningEvent.OWNED_STACK_REQUIRED,
            )
        knownByWire.forEach { (wire, event) ->
            assertEquals(wire, event.wire)
            assertTrue("event $wire should be known", event.isKnown)
        }
    }

    @Test
    fun `known direct path learning event serializes to its bare wire string`() {
        val snapshot =
            NativeRuntimeSnapshot(
                source = "proxy",
                directPathLearningSignals =
                    listOf(
                        DirectPathLearningSignal(
                            authority = "example.org:443",
                            ipSetDigest = "deadbeef",
                            event = DirectPathLearningEvent.QUIC_BLOCKED_TCP_OK,
                            capturedAt = 7L,
                        ),
                    ),
            )

        val encoded = snapshotJson.encodeToString(NativeRuntimeSnapshot.serializer(), snapshot)

        // The event encodes as the bare wire string, byte-identical to the
        // pre-value-class enum encoding -- existing goldens stay valid.
        assertTrue(
            "expected verbatim event wire string in $encoded",
            encoded.contains("\"event\":\"QUIC_BLOCKED_TCP_OK\""),
        )
    }

    @Test
    fun `snapshot without schemaVersion is rejected`() {
        val json = """{ "source": "proxy", "state": "running" }"""

        assertThrows(SerializationException::class.java) {
            forwardCompatJson.decodeFromString(NativeRuntimeSnapshot.serializer(), json)
        }
    }

    @Test
    fun `snapshot with explicit schemaVersion decodes that value`() {
        val json = """{ "source": "proxy", "state": "running", "schemaVersion": 3 }"""

        val decoded = forwardCompatJson.decodeFromString(NativeRuntimeSnapshot.serializer(), json)

        assertEquals(NativeRuntimeTelemetrySchemaVersion, decoded.schemaVersion)
        assertEquals("proxy", decoded.source)
    }

    @Test
    fun `schemaVersion coexists with unknown forward-compatible fields`() {
        // Unknown additive fields remain tolerated within the current schema.
        val json =
            """
            { "source": "proxy", "state": "running", "schemaVersion": 3,
              "futureUnknownSnapshotField": 42 }
            """.trimIndent()

        val decoded = forwardCompatJson.decodeFromString(NativeRuntimeSnapshot.serializer(), json)

        assertEquals(NativeRuntimeTelemetrySchemaVersion, decoded.schemaVersion)
        assertEquals("running", decoded.state)
    }

    @Test
    fun `ws carrier handshake counters decode from a native telemetry payload`() {
        // The AmneziaWG native runtime flattens wsCarrierHandshakes /
        // wsCarrierHandshakeFailures into its snapshot; this is the Kotlin
        // telemetry-channel surface for those counters.
        val json =
            """
            { "source": "amneziawg", "schemaVersion": 3, "state": "running",
              "wsCarrierHandshakes": 4, "wsCarrierHandshakeFailures": 1 }
            """.trimIndent()

        val decoded = forwardCompatJson.decodeFromString(NativeRuntimeSnapshot.serializer(), json)

        assertEquals(4L, decoded.wsCarrierHandshakes)
        assertEquals(1L, decoded.wsCarrierHandshakeFailures)
    }

    @Test
    fun `ws carrier handshake counters default to zero when absent`() {
        // Current native producers may omit the optional counters; the
        // defaulted Kotlin fields keep the snapshot decodable at 0.
        val json = """{ "source": "amneziawg", "schemaVersion": 3, "state": "running" }"""

        val decoded = forwardCompatJson.decodeFromString(NativeRuntimeSnapshot.serializer(), json)

        assertEquals(0L, decoded.wsCarrierHandshakes)
        assertEquals(0L, decoded.wsCarrierHandshakeFailures)
    }

    @Test
    fun `default snapshot encodes current schemaVersion`() {
        val encoded =
            snapshotJson.encodeToString(
                NativeRuntimeSnapshot.serializer(),
                NativeRuntimeSnapshot(source = "proxy"),
            )

        assertTrue(
            "expected schemaVersion in $encoded",
            encoded.contains("\"schemaVersion\":3"),
        )
    }

    private companion object {
        val snapshotJson =
            kotlinx.serialization.json.Json {
                explicitNulls = true
                encodeDefaults = true
            }

        // Mirrors the production engine telemetry decoders (RipDpiProxy,
        // Tun2SocksTunnel, RipDpiWarp, RipDpiRelay), which decode the native
        // telemetry snapshot with ignoreUnknownKeys = true. See
        // docs/architecture/TELEMETRY_CONTRACT.md, "Forward compatibility".
        val forwardCompatJson =
            kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
            }
    }
}
