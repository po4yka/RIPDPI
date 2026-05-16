package com.poyka.ripdpi.services.redaction

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardShareGuardTest {
    @Test
    fun `plain safe text is allowed`() {
        val verdict = ClipboardShareGuard.audit("connectivity test passed")
        assertEquals(GuardVerdict.Allow, verdict)
    }

    @Test
    fun `text containing uuid warns`() {
        // 550e8400-e29b-41d4-a716-446655440000 is a fixture UUID in standard 8-4-4-4-12 format
        val verdict =
            ClipboardShareGuard.audit("profile 550e8400-e29b-41d4-a716-446655440000 loaded")
        assertEquals(GuardVerdict.Warn, verdict)
    }

    @Test
    fun `text containing subscription token query param warns`() {
        val verdict =
            ClipboardShareGuard.audit("https://cdn.example.com/config?token=fixture-subscription-token-1")
        assertEquals(GuardVerdict.Warn, verdict)
    }

    @Test
    fun `text containing password query param warns`() {
        val verdict =
            ClipboardShareGuard.audit("connect password=fixture-password-3")
        assertEquals(GuardVerdict.Warn, verdict)
    }

    @Test
    fun `text containing credential url is cleared`() {
        val verdict =
            ClipboardShareGuard.audit("vless://fixture-user:fixture-secret@vpn.example.com:443")
        assertEquals(GuardVerdict.Clear, verdict)
    }

    @Test
    fun `credential url with embedded token clears rather than warns`() {
        val verdict =
            ClipboardShareGuard.audit("https://fixture-user:fixture-subscription-token-2@api.example.com/sub")
        assertEquals(GuardVerdict.Clear, verdict)
    }

    @Test
    fun `empty string is allowed`() {
        assertEquals(GuardVerdict.Allow, ClipboardShareGuard.audit(""))
    }

    @Test
    fun `server hostname without credentials is allowed`() {
        assertEquals(GuardVerdict.Allow, ClipboardShareGuard.audit("vpn.example.com"))
    }
}
