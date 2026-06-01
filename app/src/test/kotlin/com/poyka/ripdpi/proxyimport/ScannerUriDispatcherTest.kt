package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [ScannerUriDispatcher]: the QR-scanner decoded-text classifier.
 * A decoded payload is validated against the proxy-URI allowlist and mapped to a
 * [ProxyImportRequest]; invalid content produces a redacted error (first 16 chars only)
 * so the full payload is never surfaced or logged.
 */
class ScannerUriDispatcherTest {
    @Test
    fun `allowlisted vless qr payload resolves to a profile import request`() {
        val request =
            ScannerUriDispatcher.dispatch(
                "vless://11111111-2222-3333-4444-555555555555@example.com:443#QR",
            )

        assertTrue(request is ScannerScanResult.Profile)
        assertTrue((request as ScannerScanResult.Profile).request.profile is ProxyProfile.Vless)
    }

    @Test
    fun `allowlisted shadowsocks qr payload resolves to a profile import request`() {
        val request =
            ScannerUriDispatcher.dispatch(
                "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@example.com:8388#QR",
            )

        assertTrue(request is ScannerScanResult.Profile)
    }

    @Test
    fun `non allowlisted scheme yields a redacted invalid result`() {
        val payload = "http://malicious.example.com/very/long/path/that/should/be/redacted"

        val result = ScannerUriDispatcher.dispatch(payload)

        assertTrue(result is ScannerScanResult.Invalid)
        result as ScannerScanResult.Invalid
        // Redaction caps the preview at the first 16 characters of the payload.
        assertEquals("http://malicious", result.redactedPreview)
    }

    @Test
    fun `redaction caps preview at 16 characters`() {
        val result = ScannerUriDispatcher.dispatch("this-is-not-a-uri-at-all-and-is-very-long")

        assertTrue(result is ScannerScanResult.Invalid)
        assertTrue((result as ScannerScanResult.Invalid).redactedPreview.length <= 16)
    }

    @Test
    fun `short invalid payload is previewed in full`() {
        val result = ScannerUriDispatcher.dispatch("nope")

        assertTrue(result is ScannerScanResult.Invalid)
        assertEquals("nope", (result as ScannerScanResult.Invalid).redactedPreview)
    }

    @Test
    fun `structurally broken allowlisted scheme is treated as invalid, never throws`() {
        val result = ScannerUriDispatcher.dispatch("vless://")

        assertTrue(result is ScannerScanResult.Invalid)
    }

    @Test
    fun `blank payload is invalid`() {
        assertTrue(ScannerUriDispatcher.dispatch("   ") is ScannerScanResult.Invalid)
    }

    @Test
    fun `allowlist covers the scanner-supported proxy schemes`() {
        // The scanner prefilter is narrower than clipboard/share-sheet import.
        val schemes = ScannerUriDispatcher.allowedSchemes
        assertTrue(schemes.containsAll(listOf("vless", "trojan", "ss", "hysteria2", "tuic")))
        // Removed legacy schemes are no longer prefiltered.
        assertFalse(schemes.contains("vmess"))
        assertFalse(schemes.contains("trojan-go"))
        assertFalse(schemes.contains("hysteria"))
        // https is explicitly not a proxy-node scheme.
        assertFalse(schemes.contains("https"))
    }
}
