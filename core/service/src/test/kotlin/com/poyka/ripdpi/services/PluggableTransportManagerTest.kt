package com.poyka.ripdpi.services

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluggableTransportManagerTest {
    @Test
    fun `parseObfs4BridgeLine extracts endpoint and client options`() {
        val parsed =
            parseObfs4BridgeLine(
                "Bridge obfs4 203.0.113.7:443 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA cert=abcd1234 iat-mode=2",
            )

        assertEquals("203.0.113.7", parsed.host)
        assertEquals(443, parsed.port)
        assertEquals("abcd1234", parsed.cert)
        assertEquals(2, parsed.iatMode)
    }

    @Test
    fun `encodePtArguments escapes separators in values`() {
        val encoded =
            encodePtArguments(
                "url" to "https://example.com/path;param",
                "front" to "cdn\\edge",
            )

        assertEquals("url=https://example.com/path\\;param;front=cdn\\\\edge", encoded)
    }

    @Test
    fun `splitUrlTarget infers default https port`() {
        val (host, port) = splitUrlTarget("https://edge.example/websocket")

        assertEquals("edge.example", host)
        assertEquals(443, port)
    }

    @Test
    fun `parseManagedClientListenerLine accepts matching cmethod`() {
        val listener = parseManagedClientListenerLine("CMETHOD webtunnel socks5 127.0.0.1:43123", "webtunnel")

        assertEquals("127.0.0.1", listener?.hostString)
        assertEquals(43123, listener?.port)
    }

    @Test
    fun `parseManagedClientListenerLine ignores unrelated output`() {
        assertNull(parseManagedClientListenerLine("CMETHOD snowflake socks5 127.0.0.1:43123", "obfs4"))
        assertNull(parseManagedClientListenerLine("[NOTICE]: launched", "snowflake"))
    }

    @Test
    fun `splitPtAuthArgs keeps payload within RFC1929 field sizes`() {
        val payload = "a".repeat(320)

        val (username, password) = splitPtAuthArgs(payload)

        assertEquals(255, username.size)
        assertEquals(65, password.size)
        assertArrayEquals(payload.toByteArray(Charsets.UTF_8), username + password)
    }
}
