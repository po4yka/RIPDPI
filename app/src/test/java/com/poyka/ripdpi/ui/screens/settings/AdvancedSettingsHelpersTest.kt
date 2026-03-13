package com.poyka.ripdpi.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedSettingsHelpersTest {

    // -- formatHttpFakeProfileLabel --

    @Test
    fun `http profile iana_get maps to IANA GET`() {
        assertEquals("IANA GET", formatHttpFakeProfileLabel("iana_get"))
    }

    @Test
    fun `http profile cloudflare_get maps to Cloudflare GET`() {
        assertEquals("Cloudflare GET", formatHttpFakeProfileLabel("cloudflare_get"))
    }

    @Test
    fun `http profile unknown value falls back to compatibility default`() {
        assertEquals("Compatibility default", formatHttpFakeProfileLabel("unknown_profile"))
    }

    // -- formatTlsFakeProfileLabel --

    @Test
    fun `tls profile iana_firefox maps to IANA Firefox`() {
        assertEquals("IANA Firefox", formatTlsFakeProfileLabel("iana_firefox"))
    }

    @Test
    fun `tls profile google_chrome maps to Google Chrome`() {
        assertEquals("Google Chrome", formatTlsFakeProfileLabel("google_chrome"))
    }

    @Test
    fun `tls profile rutracker_kyber maps to Rutracker Kyber`() {
        assertEquals("Rutracker Kyber", formatTlsFakeProfileLabel("rutracker_kyber"))
    }

    @Test
    fun `tls profile unknown value falls back to compatibility default`() {
        assertEquals("Compatibility default", formatTlsFakeProfileLabel("unknown"))
    }

    // -- formatUdpFakeProfileLabel --

    @Test
    fun `udp profile dns_query maps to DNS query`() {
        assertEquals("DNS query", formatUdpFakeProfileLabel("dns_query"))
    }

    @Test
    fun `udp profile wireguard_initiation maps to WireGuard initiation`() {
        assertEquals("WireGuard initiation", formatUdpFakeProfileLabel("wireguard_initiation"))
    }

    @Test
    fun `udp profile unknown value falls back to compatibility default`() {
        assertEquals("Compatibility default", formatUdpFakeProfileLabel("other"))
    }

    // -- isActivationBoundaryValid --

    @Test
    fun `blank value is valid`() {
        assertTrue(isActivationBoundaryValid("", minValue = 0))
    }

    @Test
    fun `whitespace-only value is valid`() {
        assertTrue(isActivationBoundaryValid("   ", minValue = 0))
    }

    @Test
    fun `value at minimum is valid`() {
        assertTrue(isActivationBoundaryValid("10", minValue = 10))
    }

    @Test
    fun `value above minimum is valid`() {
        assertTrue(isActivationBoundaryValid("100", minValue = 10))
    }

    @Test
    fun `value below minimum is invalid`() {
        assertFalse(isActivationBoundaryValid("5", minValue = 10))
    }

    @Test
    fun `non-numeric value is invalid`() {
        assertFalse(isActivationBoundaryValid("abc", minValue = 0))
    }
}
