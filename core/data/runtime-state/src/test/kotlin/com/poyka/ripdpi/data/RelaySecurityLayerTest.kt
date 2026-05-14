package com.poyka.ripdpi.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySecurityLayerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `relay profile record carries security layer independent of transport`() {
        val plainTlsXhttp =
            RelayProfileRecord(
                id = "p1",
                kind = RelayKindVless,
                securityLayer = RelaySecurityLayerTls,
                server = "edge.example.com",
                serverName = "edge.example.com",
                vlessTransport = RelayVlessTransportXhttp,
                xhttpPath = "/xhttp",
                xhttpHost = "origin.example.com",
            )

        // securityLayer and transport are orthogonal: tls + xhttp is representable.
        assertEquals(RelaySecurityLayerTls, plainTlsXhttp.securityLayer)
        assertEquals(RelayVlessTransportXhttp, plainTlsXhttp.vlessTransport)
        assertTrue(plainTlsXhttp.realityPublicKey.isEmpty())
        assertTrue(plainTlsXhttp.realityShortId.isEmpty())

        val realityTcp =
            RelayProfileRecord(
                id = "p0",
                kind = RelayKindVlessReality,
                securityLayer = RelaySecurityLayerReality,
                server = "relay.example.com",
                serverName = "relay.example.com",
                realityPublicKey = "public-key",
                realityShortId = "short-id",
                vlessTransport = RelayVlessTransportRealityTcp,
            )
        assertEquals(RelaySecurityLayerReality, realityTcp.securityLayer)
        assertNotEquals(plainTlsXhttp.securityLayer, realityTcp.securityLayer)
    }

    @Test
    fun `relay profile record default security layer preserves reality semantics`() {
        // A record constructed without an explicit securityLayer must decode the
        // same way a legacy on-disk record (which omits the field) would: today
        // the only VLESS shape is Reality, so the safe default is reality.
        val defaulted = RelayProfileRecord(id = "default", kind = RelayKindVlessReality)
        assertEquals(RelaySecurityLayerReality, defaulted.securityLayer)
    }

    @Test
    fun `relay profile record round trips security layer through json`() {
        val record =
            RelayProfileRecord(
                id = "p1",
                kind = RelayKindVless,
                securityLayer = RelaySecurityLayerTls,
                server = "edge.example.com",
                serverName = "edge.example.com",
                vlessTransport = RelayVlessTransportXhttp,
                xhttpPath = "/xhttp",
                xhttpHost = "origin.example.com",
            )

        val encoded = json.encodeToString(RelayProfileRecord.serializer(), record)
        val decoded = json.decodeFromString(RelayProfileRecord.serializer(), encoded)

        assertEquals(record, decoded)
        assertEquals(RelaySecurityLayerTls, decoded.securityLayer)
    }

    @Test
    fun `legacy json without security layer decodes as reality`() {
        // Simulates an on-disk record written before securityLayer existed.
        val legacyJson =
            """{"id":"default","kind":"vless_reality","realityPublicKey":"pk","realityShortId":"sid"}"""
        val decoded = json.decodeFromString(RelayProfileRecord.serializer(), legacyJson)
        assertEquals(RelaySecurityLayerReality, decoded.securityLayer)
    }
}
