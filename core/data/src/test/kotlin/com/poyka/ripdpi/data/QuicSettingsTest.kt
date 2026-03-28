package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuicSettingsTest {
    // -- normalizeQuicInitialMode --

    @Test
    fun `normalizeQuicInitialMode returns known mode unchanged`() {
        assertEquals(QuicInitialModeDisabled, normalizeQuicInitialMode("disabled"))
        assertEquals(QuicInitialModeRoute, normalizeQuicInitialMode("route"))
        assertEquals(QuicInitialModeRouteAndCache, normalizeQuicInitialMode("route_and_cache"))
    }

    @Test
    fun `normalizeQuicInitialMode lowercases and trims`() {
        assertEquals(QuicInitialModeRoute, normalizeQuicInitialMode("  ROUTE  "))
    }

    @Test
    fun `normalizeQuicInitialMode falls back to route_and_cache for unknown`() {
        assertEquals(QuicInitialModeRouteAndCache, normalizeQuicInitialMode("invalid"))
        assertEquals(QuicInitialModeRouteAndCache, normalizeQuicInitialMode(""))
    }

    // -- quicInitialModeUsesRouting --

    @Test
    fun `quicInitialModeUsesRouting returns false for disabled`() {
        assertFalse(quicInitialModeUsesRouting(QuicInitialModeDisabled))
    }

    @Test
    fun `quicInitialModeUsesRouting returns true for route`() {
        assertTrue(quicInitialModeUsesRouting(QuicInitialModeRoute))
    }

    @Test
    fun `quicInitialModeUsesRouting returns true for route_and_cache`() {
        assertTrue(quicInitialModeUsesRouting(QuicInitialModeRouteAndCache))
    }

    // -- quicInitialModeCachesHosts --

    @Test
    fun `quicInitialModeCachesHosts returns true only for route_and_cache`() {
        assertTrue(quicInitialModeCachesHosts(QuicInitialModeRouteAndCache))
        assertFalse(quicInitialModeCachesHosts(QuicInitialModeRoute))
        assertFalse(quicInitialModeCachesHosts(QuicInitialModeDisabled))
    }

    // -- normalizeQuicFakeProfile --

    @Test
    fun `normalizeQuicFakeProfile returns known profile unchanged`() {
        assertEquals(QuicFakeProfileDisabled, normalizeQuicFakeProfile("disabled"))
        assertEquals(QuicFakeProfileCompatDefault, normalizeQuicFakeProfile("compat_default"))
        assertEquals(QuicFakeProfileRealisticInitial, normalizeQuicFakeProfile("realistic_initial"))
    }

    @Test
    fun `normalizeQuicFakeProfile lowercases and trims`() {
        assertEquals(QuicFakeProfileCompatDefault, normalizeQuicFakeProfile("  COMPAT_DEFAULT  "))
    }

    @Test
    fun `normalizeQuicFakeProfile falls back to disabled for unknown`() {
        assertEquals(QuicFakeProfileDisabled, normalizeQuicFakeProfile("nonexistent"))
        assertEquals(QuicFakeProfileDisabled, normalizeQuicFakeProfile(""))
    }

    // -- normalizeQuicFakeHost --

    @Test
    fun `normalizeQuicFakeHost lowercases and trims trailing dot`() {
        assertEquals("www.example.com", normalizeQuicFakeHost("WWW.Example.COM."))
    }

    @Test
    fun `normalizeQuicFakeHost rejects empty string`() {
        assertEquals("", normalizeQuicFakeHost(""))
        assertEquals("", normalizeQuicFakeHost("   "))
    }

    @Test
    fun `normalizeQuicFakeHost rejects host with colon`() {
        assertEquals("", normalizeQuicFakeHost("host:8080"))
    }

    @Test
    fun `normalizeQuicFakeHost rejects leading dot`() {
        assertEquals("", normalizeQuicFakeHost(".example.com"))
    }

    @Test
    fun `normalizeQuicFakeHost rejects double dots`() {
        assertEquals("", normalizeQuicFakeHost("example..com"))
    }

    @Test
    fun `normalizeQuicFakeHost rejects label starting with dash`() {
        assertEquals("", normalizeQuicFakeHost("-example.com"))
    }

    @Test
    fun `normalizeQuicFakeHost rejects label ending with dash`() {
        assertEquals("", normalizeQuicFakeHost("example-.com"))
    }

    @Test
    fun `normalizeQuicFakeHost rejects IPv4 literal`() {
        assertEquals("", normalizeQuicFakeHost("192.168.1.1"))
        assertEquals("", normalizeQuicFakeHost("0.0.0.0"))
    }

    @Test
    fun `normalizeQuicFakeHost accepts valid hostname`() {
        assertEquals("www.wikipedia.org", normalizeQuicFakeHost("www.wikipedia.org"))
        assertEquals("cdn-1.example.com", normalizeQuicFakeHost("cdn-1.example.com"))
    }

    @Test
    fun `normalizeQuicFakeHost rejects non-hostname characters`() {
        assertEquals("", normalizeQuicFakeHost("host_name.com"))
        assertEquals("", normalizeQuicFakeHost("host name.com"))
    }
}
