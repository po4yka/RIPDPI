package com.poyka.ripdpi.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionQualitySnapshotTest {
    @Test
    fun `connection quality snapshot round trips through kotlinx json`() {
        val original =
            ConnectionQualitySnapshot(
                lossPct = 1.5f,
                rttP50Ms = 42,
                rttP95Ms = 120,
                jitterMs = 8,
                sampleCount = 24,
                windowStartAtMs = 1_716_576_000_000L,
                transportKind = TransportKind.TcpTunnel,
            )

        val json = snapshotJson.encodeToString(ConnectionQualitySnapshot.serializer(), original)

        // Wire-shape assertions: camelCase keys, snake_case TransportKind value.
        assertTrue("lossPct should serialise camelCase: $json", json.contains("\"lossPct\":1.5"))
        assertTrue("rttP50Ms should serialise camelCase: $json", json.contains("\"rttP50Ms\":42"))
        assertTrue("rttP95Ms should serialise camelCase: $json", json.contains("\"rttP95Ms\":120"))
        assertTrue("jitterMs should serialise camelCase: $json", json.contains("\"jitterMs\":8"))
        assertTrue("sampleCount should serialise camelCase: $json", json.contains("\"sampleCount\":24"))
        assertTrue(
            "windowStartAtMs should serialise camelCase: $json",
            json.contains("\"windowStartAtMs\":1716576000000"),
        )
        assertTrue(
            "transportKind should serialise snake_case enum value: $json",
            json.contains("\"transportKind\":\"tcp_tunnel\""),
        )

        val parsed = snapshotJson.decodeFromString(ConnectionQualitySnapshot.serializer(), json)
        assertEquals(original, parsed)
    }

    @Test
    fun `native runtime snapshot accepts the exact wire shape produced by rust ripdpi-quality`() {
        // Verify the Kotlin decoder tolerates the exact JSON shape the Rust
        // producer emits — mirrors the producer's serialisation test in
        // native/rust/crates/ripdpi-quality (snake_case TransportKind +
        // camelCase ConnectionQualitySnapshot fields).
        val rustWire =
            """
            {
              "source": "tunnel",
              "state": "running",
              "connectionQuality": {
                "lossPct": 0.5,
                "rttP50Ms": 28,
                "rttP95Ms": 95,
                "jitterMs": 4,
                "sampleCount": 100,
                "windowStartAtMs": 1716576000000,
                "transportKind": "tcp_tunnel"
              }
            }
            """.trimIndent()

        val decoded = forwardCompatJson.decodeFromString(NativeRuntimeSnapshot.serializer(), rustWire)

        assertNotNull(decoded.connectionQuality)
        val quality = decoded.connectionQuality!!
        assertEquals(0.5f, quality.lossPct)
        assertEquals(28L, quality.rttP50Ms)
        assertEquals(95L, quality.rttP95Ms)
        assertEquals(4L, quality.jitterMs)
        assertEquals(100L, quality.sampleCount)
        assertEquals(1_716_576_000_000L, quality.windowStartAtMs)
        assertEquals(TransportKind.TcpTunnel, quality.transportKind)
    }

    @Test
    fun `native runtime snapshot decodes when connection quality is omitted`() {
        // The Rust side wraps the field in Option<...> with
        // #[serde(skip_serializing_if = "Option::is_none")] so an idle runtime
        // emits no key at all. The Kotlin default must keep the snapshot
        // decodable in that case.
        val json = """{ "source": "tunnel", "state": "running" }"""

        val decoded = forwardCompatJson.decodeFromString(NativeRuntimeSnapshot.serializer(), json)

        assertNull(decoded.connectionQuality)
    }

    @Test
    fun `each transport kind round trips with its expected snake_case wire value`() {
        val expectedWireByVariant =
            mapOf(
                TransportKind.TcpProxy to "tcp_proxy",
                TransportKind.TcpTunnel to "tcp_tunnel",
                TransportKind.UdpRelay to "udp_relay",
            )

        expectedWireByVariant.forEach { (variant, expectedWire) ->
            val snapshot = ConnectionQualitySnapshot(transportKind = variant)
            val encoded = snapshotJson.encodeToString(ConnectionQualitySnapshot.serializer(), snapshot)
            assertTrue(
                "expected $variant to encode as \"$expectedWire\" in $encoded",
                encoded.contains("\"transportKind\":\"$expectedWire\""),
            )
            val decoded = snapshotJson.decodeFromString(ConnectionQualitySnapshot.serializer(), encoded)
            assertEquals(variant, decoded.transportKind)
        }
    }

    private companion object {
        val snapshotJson =
            Json {
                explicitNulls = true
                encodeDefaults = true
            }

        // Mirrors the production engine telemetry decoders — see
        // docs/architecture/TELEMETRY_CONTRACT.md "Forward compatibility".
        val forwardCompatJson =
            Json {
                ignoreUnknownKeys = true
            }
    }
}
