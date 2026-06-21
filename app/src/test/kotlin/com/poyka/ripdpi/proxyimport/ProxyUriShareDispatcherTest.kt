package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [ProxyUriShareDispatcher]: a proxy share-link URI string is
 * classified into an [ProxyImportRequest] that the handler activity routes to the
 * profile-import confirmation destination. Unknown / malformed schemes resolve to a
 * typed [ProxyImportRequest.UnsupportedScheme] rather than throwing.
 */
class ProxyUriShareDispatcherTest {
    @Test
    fun `vless uri resolves to a profile import request`() {
        val request =
            ProxyUriShareDispatcher.dispatch(
                "vless://11111111-2222-3333-4444-555555555555@example.com:443#Tokyo",
            )

        assertTrue(request is ProxyImportRequest.Profile)
        val profile = (request as ProxyImportRequest.Profile).profile
        assertTrue(profile is ProxyProfile.Vless)
        assertEquals("Tokyo", profile.displayName)
    }

    @Test
    fun `vless reality uri without pbk is rejected before profile import`() {
        val request =
            ProxyUriShareDispatcher.dispatch(
                "vless://11111111-2222-3333-4444-555555555555@example.com:443?security=reality&sni=cdn.example#Broken",
            )

        assertTrue(request is ProxyImportRequest.UnsupportedScheme)
        assertEquals("vless", (request as ProxyImportRequest.UnsupportedScheme).scheme)
    }

    @Test
    fun `shadowsocks uri resolves to a profile import request`() {
        val request =
            ProxyUriShareDispatcher.dispatch(
                "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@example.com:8388#Edge",
            )

        assertTrue(request is ProxyImportRequest.Profile)
        assertTrue((request as ProxyImportRequest.Profile).profile is ProxyProfile.Shadowsocks)
    }

    @Test
    fun `trojan uri resolves to a profile import request`() {
        val request = ProxyUriShareDispatcher.dispatch("trojan://secret@example.com:443#Relay")

        assertTrue(request is ProxyImportRequest.Profile)
        assertTrue((request as ProxyImportRequest.Profile).profile is ProxyProfile.Trojan)
    }

    @Test
    fun `hysteria2 uri resolves to a profile import request`() {
        val request = ProxyUriShareDispatcher.dispatch("hysteria2://pass@example.com:443#Fast")

        assertTrue(request is ProxyImportRequest.Profile)
        assertTrue((request as ProxyImportRequest.Profile).profile is ProxyProfile.Hysteria2)
    }

    @Test
    fun `unknown scheme falls through to a typed unsupported result`() {
        val request = ProxyUriShareDispatcher.dispatch("gopher://example.com:70")

        assertTrue(request is ProxyImportRequest.UnsupportedScheme)
        assertEquals("gopher", (request as ProxyImportRequest.UnsupportedScheme).scheme)
    }

    @Test
    fun `structurally malformed known scheme falls through to unsupported, never throws`() {
        val request = ProxyUriShareDispatcher.dispatch("vless://")

        assertTrue(request is ProxyImportRequest.UnsupportedScheme)
    }

    @Test
    fun `blank input falls through to unsupported`() {
        assertTrue(ProxyUriShareDispatcher.dispatch("   ") is ProxyImportRequest.UnsupportedScheme)
    }

    @Test
    fun `scheme matching is case insensitive`() {
        val request =
            ProxyUriShareDispatcher.dispatch(
                "VLESS://11111111-2222-3333-4444-555555555555@example.com:443#Up",
            )

        assertTrue(request is ProxyImportRequest.Profile)
    }
}
