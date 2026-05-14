package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [ClipboardImportClassifier]: the one-shot classifier behind the
 * explicit "Import from clipboard" menu action. It is invoked only after the user taps
 * the menu entry — the clipboard is never watched or polled. Classification reuses the
 * shared [com.poyka.ripdpi.data.uri.ProxyUriCodec]; unknown content surfaces a typed
 * error carrying only the scheme prefix, never the payload.
 */
class ClipboardImportClassifierTest {
    @Test
    fun `vless clipboard content classifies as an importable profile`() {
        val result =
            ClipboardImportClassifier.classify(
                "vless://11111111-2222-3333-4444-555555555555@example.com:443#Clip",
            )

        assertTrue(result is ClipboardImportResult.Importable)
        assertTrue((result as ClipboardImportResult.Importable).request.profile is ProxyProfile.Vless)
    }

    @Test
    fun `surrounding whitespace is trimmed before parsing`() {
        val result =
            ClipboardImportClassifier.classify(
                "  \n trojan://pass@trojan.example.com:443#Clip \n ",
            )

        assertTrue(result is ClipboardImportResult.Importable)
    }

    @Test
    fun `unknown scheme yields a typed error carrying only the scheme`() {
        val result =
            ClipboardImportClassifier.classify("http://malicious.example.com/secret/path?token=leak")

        assertTrue(result is ClipboardImportResult.UnknownContent)
        // The error carries the scheme it found, never the payload itself.
        assertEquals("http", (result as ClipboardImportResult.UnknownContent).scheme)
    }

    @Test
    fun `content with no scheme yields an empty-scheme error`() {
        val result = ClipboardImportClassifier.classify("just some random clipboard text")

        assertTrue(result is ClipboardImportResult.UnknownContent)
        assertEquals("", (result as ClipboardImportResult.UnknownContent).scheme)
    }

    @Test
    fun `blank clipboard content yields the empty result`() {
        assertEquals(ClipboardImportResult.Empty, ClipboardImportClassifier.classify("   "))
        assertEquals(ClipboardImportResult.Empty, ClipboardImportClassifier.classify(""))
    }

    @Test
    fun `null clipboard content yields the empty result`() {
        assertEquals(ClipboardImportResult.Empty, ClipboardImportClassifier.classify(null))
    }

    @Test
    fun `structurally broken known scheme is unknown content, never throws`() {
        val result = ClipboardImportClassifier.classify("vless://")

        assertTrue(result is ClipboardImportResult.UnknownContent)
        assertEquals("vless", (result as ClipboardImportResult.UnknownContent).scheme)
    }
}
