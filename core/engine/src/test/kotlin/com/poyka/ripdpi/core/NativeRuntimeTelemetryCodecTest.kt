package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.NativeRuntimeTelemetrySchemaVersion
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeRuntimeTelemetryCodecTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `current schema accepts unknown additive fields`() {
        val snapshot =
            json.decodeNativeRuntimeSnapshot(
                """{"source":"proxy","schemaVersion":3,"futureField":true}""",
            )

        assertEquals(NativeRuntimeTelemetrySchemaVersion, snapshot.schemaVersion)
        assertEquals("proxy", snapshot.source)
    }

    @Test
    fun `missing schema version is rejected`() {
        assertThrows(SerializationException::class.java) {
            json.decodeNativeRuntimeSnapshot("""{"source":"proxy"}""")
        }
    }

    @Test
    fun `non-current schema versions are rejected`() {
        listOf(1, 2, 4).forEach { version ->
            assertThrows(IllegalArgumentException::class.java) {
                json.decodeNativeRuntimeSnapshot(
                    """{"source":"proxy","schemaVersion":$version}""",
                )
            }
        }
    }

    @Test
    fun `proxy forwarding evidence decodes additive privacy-safe counters`() {
        val evidence =
            json.decodeProxyForwardingEvidence(
                """
                {
                  "proxyClientSocketsAccepted": 2,
                  "upstreamSocketCreated": 3,
                  "upstreamOpened": 1,
                  "upstreamOpenFailures": 2,
                  "protectAttempted": 3,
                  "protectSucceeded": 1,
                  "protectRejected": 1,
                  "protectErrors": 1,
                  "upstreamApplicationBytes": 512,
                  "firstUpstreamApplicationForwardedAt": 1000,
                  "lastUpstreamApplicationForwardedAt": 1200,
                  "futureField": true
                }
                """.trimIndent(),
            )

        assertEquals(2L, evidence.proxyClientSocketsAccepted)
        assertEquals(3L, evidence.upstreamSocketCreated)
        assertEquals(1L, evidence.upstreamOpened)
        assertEquals(2L, evidence.upstreamOpenFailures)
        assertEquals(3L, evidence.protectAttempted)
        assertEquals(1L, evidence.protectSucceeded)
        assertEquals(1L, evidence.protectRejected)
        assertEquals(1L, evidence.protectErrors)
        assertEquals(512L, evidence.upstreamApplicationBytes)
        assertEquals(1000L, evidence.firstUpstreamApplicationForwardedAt)
        assertEquals(1200L, evidence.lastUpstreamApplicationForwardedAt)
    }

    @Test
    fun `missing proxy forwarding evidence fields default to zero`() {
        assertEquals(ProxyForwardingEvidence.Empty, json.decodeProxyForwardingEvidence("{}"))
    }
}
