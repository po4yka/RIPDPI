package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiRelayConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun `parseObfs4BridgeLine treats the fingerprint as optional`() {
        val parsed = parseObfs4BridgeLine("obfs4 192.0.2.1:443 cert=abcd1234 iat-mode=1")

        assertEquals("192.0.2.1", parsed.host)
        assertEquals(443, parsed.port)
        assertEquals("abcd1234", parsed.cert)
        assertEquals(1, parsed.iatMode)
    }

    @Test
    fun `parseObfs4BridgeLine strips IPv6 brackets`() {
        val parsed = parseObfs4BridgeLine("Bridge obfs4 [2001:db8::1]:443 cert=abcd1234")

        assertEquals("2001:db8::1", parsed.host)
        assertEquals(443, parsed.port)
        assertEquals(0, parsed.iatMode)
    }

    @Test
    fun `parseObfs4BridgeLine rejects invalid iat-mode port and duplicate options`() {
        listOf(
            "obfs4 192.0.2.1:443 cert=abcd1234 iat-mode=paranoid",
            "obfs4 192.0.2.1:443 cert=abcd1234 iat-mode=3",
            "obfs4 192.0.2.1:https cert=abcd1234",
            "obfs4 192.0.2.1:70000 cert=abcd1234",
            "obfs4 192.0.2.1:443 cert=abcd1234 cert=efgh5678",
        ).forEach { line ->
            assertThrows(line, IllegalArgumentException::class.java) { parseObfs4BridgeLine(line) }
        }
    }

    @Test
    fun `tor managed transports cover every shipped bridge transport`() {
        fun names(
            bridgeLine: String,
            webTunnelUrl: String = "",
        ) = torManagedTransportNames(RipDpiRelayConfig(ptBridgeLine = bridgeLine, ptWebTunnelUrl = webTunnelUrl))

        assertEquals(listOf("snowflake"), names("Bridge snowflake 192.0.2.3:80 url=https://broker.example/"))
        assertEquals(listOf("obfs4"), names("obfs4 192.0.2.1:443 cert=abcd1234"))
        assertEquals(
            listOf("obfs4", "webtunnel"),
            names("obfs4 192.0.2.1:443 cert=abcd1234", webTunnelUrl = "https://edge.example/ws"),
        )
        assertEquals(listOf("webtunnel"), names("webtunnel 192.0.2.4:443 url=https://edge.example/ws"))
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

    @Test
    fun `managed client connect request preserves domain target`() {
        val encoded = encodeManagedClientSocksConnectRequest("example.com", 443)

        assertArrayEquals(
            byteArrayOf(
                0x05,
                0x01,
                0x00,
                0x03,
                11,
                'e'.code.toByte(),
                'x'.code.toByte(),
                'a'.code.toByte(),
                'm'.code.toByte(),
                'p'.code.toByte(),
                'l'.code.toByte(),
                'e'.code.toByte(),
                '.'.code.toByte(),
                'c'.code.toByte(),
                'o'.code.toByte(),
                'm'.code.toByte(),
                0x01,
                0xbb.toByte(),
            ),
            encoded,
        )
    }

    @Test
    fun `managed client connect request encodes numeric ipv4 without dns`() {
        val encoded = encodeManagedClientSocksConnectRequest("203.0.113.9", 9001)

        assertArrayEquals(
            byteArrayOf(
                0x05,
                0x01,
                0x00,
                0x01,
                203.toByte(),
                0,
                113,
                9,
                0x23,
                0x29,
            ),
            encoded,
        )
    }
}
