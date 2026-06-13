package com.poyka.ripdpi.services

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PluggableTransportSocksBridgeWireTest {
    // -------------------------------------------------------------------------
    // parseManagedClientListenerLine
    // -------------------------------------------------------------------------

    @Test
    fun `parseManagedClientListenerLine returns address for well-formed webtunnel line`() {
        val result = parseManagedClientListenerLine("CMETHOD webtunnel socks5 127.0.0.1:43123", "webtunnel")
        assertEquals(InetSocketAddress("127.0.0.1", 43123), result)
    }

    @Test
    fun `parseManagedClientListenerLine returns null when method name does not match`() {
        val result = parseManagedClientListenerLine("CMETHOD webtunnel socks5 127.0.0.1:43123", "obfs4")
        assertNull(result)
    }

    @Test
    fun `parseManagedClientListenerLine returns null when protocol is not socks5`() {
        val result = parseManagedClientListenerLine("CMETHOD webtunnel socks4 127.0.0.1:43123", "webtunnel")
        assertNull(result)
    }

    @Test
    fun `parseManagedClientListenerLine returns null when field count is wrong (extra token)`() {
        val result = parseManagedClientListenerLine("CMETHOD webtunnel socks5 127.0.0.1:43123 extra", "webtunnel")
        assertNull(result)
    }

    @Test
    fun `parseManagedClientListenerLine returns null when field count is wrong (missing token)`() {
        val result = parseManagedClientListenerLine("CMETHOD webtunnel socks5", "webtunnel")
        assertNull(result)
    }

    @Test
    fun `parseManagedClientListenerLine returns null when address field has no colon`() {
        val result = parseManagedClientListenerLine("CMETHOD webtunnel socks5 127.0.0.143123", "webtunnel")
        assertNull(result)
    }

    @Test
    fun `parseManagedClientListenerLine returns null when colon is at the end of address field`() {
        // delimiter == lastIndex → guard fires → null
        val result = parseManagedClientListenerLine("CMETHOD webtunnel socks5 127.0.0.1:", "webtunnel")
        assertNull(result)
    }

    @Test
    fun `parseManagedClientListenerLine returns null when port is non-numeric`() {
        val result = parseManagedClientListenerLine("CMETHOD webtunnel socks5 127.0.0.1:notaport", "webtunnel")
        assertNull(result)
    }

    // -------------------------------------------------------------------------
    // splitPtAuthArgs
    // -------------------------------------------------------------------------

    @Test
    fun `splitPtAuthArgs empty string produces empty first segment and null-byte second`() {
        val (first, second) = splitPtAuthArgs("")
        assertArrayEquals(byteArrayOf(), first)
        assertArrayEquals(byteArrayOf(0x00), second)
    }

    @Test
    fun `splitPtAuthArgs exactly 255 bytes stays in first segment with null-byte second`() {
        val input = "A".repeat(255)
        val (first, second) = splitPtAuthArgs(input)
        assertArrayEquals(input.toByteArray(Charsets.UTF_8), first)
        assertArrayEquals(byteArrayOf(0x00), second)
    }

    @Test
    fun `splitPtAuthArgs 256 bytes splits into 255 plus 1`() {
        val input = "B".repeat(256)
        val bytes = input.toByteArray(Charsets.UTF_8)
        val (first, second) = splitPtAuthArgs(input)
        assertArrayEquals(bytes.copyOfRange(0, 255), first)
        assertArrayEquals(bytes.copyOfRange(255, 256), second)
    }

    @Test
    fun `splitPtAuthArgs exactly 510 bytes splits into 255 plus 255`() {
        val input = "C".repeat(510)
        val bytes = input.toByteArray(Charsets.UTF_8)
        val (first, second) = splitPtAuthArgs(input)
        assertArrayEquals(bytes.copyOfRange(0, 255), first)
        assertArrayEquals(bytes.copyOfRange(255, 510), second)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `splitPtAuthArgs 511 bytes throws IllegalArgumentException`() {
        splitPtAuthArgs("D".repeat(511))
    }

    @Test
    fun `splitPtAuthArgs byte-based split works correctly for multi-byte UTF-8 chars`() {
        // é is a 2-byte UTF-8 sequence (0xC3 0xA9), so 128 × é = 256 bytes but 128 chars
        val input = "é".repeat(128)
        val bytes = input.toByteArray(Charsets.UTF_8)
        assertEquals(256, bytes.size) // sanity: byte count ≠ char count
        val (first, second) = splitPtAuthArgs(input)
        assertArrayEquals(bytes.copyOfRange(0, 255), first)
        assertArrayEquals(bytes.copyOfRange(255, 256), second)
    }

    // -------------------------------------------------------------------------
    // encodeManagedClientSocksConnectRequest
    // -------------------------------------------------------------------------

    @Test
    fun `encodeManagedClientSocksConnectRequest domain host produces correct byte sequence`() {
        // Use a single-label hostname (no dots) so Robolectric's InetAddresses shadow cannot
        // misclassify it as a numeric address. The domain code path (ATYP=0x03) is exercised
        // whenever parseNumericAddressOrNull returns null — guaranteed here because parseIpv4Literal
        // requires exactly 4 dot-separated octets, and InetAddresses.isNumericAddress on a
        // non-numeric string returns false on both real Android and Robolectric.
        // Port 443 = 0x01BB
        val host = "example-host"
        val domainBytes = host.toByteArray(Charsets.UTF_8)
        val expected =
            byteArrayOf(
                0x05,
                0x01,
                0x00, // VER, CMD=CONNECT, RSV
                0x03, // ATYP=DOMAIN
                domainBytes.size.toByte(), // domain length (13 = 0x0D)
                *domainBytes, // domain bytes
                0x01,
                0xBB.toByte(), // port 443
            )
        assertArrayEquals(expected, encodeManagedClientSocksConnectRequest(host, 443))
    }

    @Test
    fun `encodeManagedClientSocksConnectRequest IPv4 literal produces correct byte sequence`() {
        // 127.0.0.1, port 1080 = 0x0438
        val expected =
            byteArrayOf(
                0x05,
                0x01,
                0x00, // VER, CMD=CONNECT, RSV
                0x01, // ATYP=IPv4
                127.toByte(),
                0,
                0,
                1, // address bytes
                0x04,
                0x38, // port 1080
            )
        assertArrayEquals(expected, encodeManagedClientSocksConnectRequest("127.0.0.1", 1080))
    }

    @Test
    fun `encodeManagedClientSocksConnectRequest IPv6 loopback produces correct byte sequence`() {
        // ::1 = 16 bytes with last byte = 1, port 443 = 0x01BB
        val expected =
            byteArrayOf(
                0x05,
                0x01,
                0x00, // VER, CMD=CONNECT, RSV
                0x04, // ATYP=IPv6
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                1, // ::1
                0x01,
                0xBB.toByte(), // port 443
            )
        assertArrayEquals(expected, encodeManagedClientSocksConnectRequest("::1", 443))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encodeManagedClientSocksConnectRequest port 0 throws IllegalArgumentException`() {
        encodeManagedClientSocksConnectRequest("example.com", 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encodeManagedClientSocksConnectRequest port 65536 throws IllegalArgumentException`() {
        encodeManagedClientSocksConnectRequest("example.com", 65536)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encodeManagedClientSocksConnectRequest domain host longer than 255 bytes throws IllegalArgumentException`() {
        // 256 ASCII chars = 256 UTF-8 bytes → exceeds limit
        encodeManagedClientSocksConnectRequest("a".repeat(256), 443)
    }
}
