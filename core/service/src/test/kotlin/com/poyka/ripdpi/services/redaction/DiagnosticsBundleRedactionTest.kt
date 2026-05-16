package com.poyka.ripdpi.services.redaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsBundleRedactionTest {
    private val fixtureBundle =
        DiagnosticsBundle(
            uuid = "fixture-uuid-1111-2222-333344445555",
            shortId = "fixture-short-id-1",
            password = "fixture-password-1",
            subscriptionToken = "fixture-subscription-token-1",
            endpoint = "fixture-endpoint-vpn.example.com:443",
            safeLabel = "connectivity-ok",
        )

    @Test
    fun `uuid field is redacted in output`() {
        val redacted = DiagnosticsRedactor.redact(fixtureBundle)
        assertEquals("redacted", redacted.uuid)
    }

    @Test
    fun `short id field is redacted in output`() {
        val redacted = DiagnosticsRedactor.redact(fixtureBundle)
        assertEquals("redacted", redacted.shortId)
    }

    @Test
    fun `password field is redacted in output`() {
        val redacted = DiagnosticsRedactor.redact(fixtureBundle)
        assertEquals("redacted", redacted.password)
    }

    @Test
    fun `subscription token field is redacted in output`() {
        val redacted = DiagnosticsRedactor.redact(fixtureBundle)
        assertEquals("redacted", redacted.subscriptionToken)
    }

    @Test
    fun `endpoint field is redacted in output`() {
        val redacted = DiagnosticsRedactor.redact(fixtureBundle)
        assertEquals("redacted", redacted.endpoint)
    }

    @Test
    fun `safe label field is preserved in output`() {
        val redacted = DiagnosticsRedactor.redact(fixtureBundle)
        assertEquals("connectivity-ok", redacted.safeLabel)
    }

    @Test
    fun `null sensitive fields remain null after redaction`() {
        val empty = DiagnosticsBundle()
        val redacted = DiagnosticsRedactor.redact(empty)
        assertNull(redacted.uuid)
        assertNull(redacted.shortId)
        assertNull(redacted.password)
        assertNull(redacted.subscriptionToken)
        assertNull(redacted.endpoint)
    }

    @Test
    fun `redacted output does not contain fixture uuid value`() {
        val redacted = DiagnosticsRedactor.redact(fixtureBundle)
        val serialized = redacted.toString()
        val fixtureUuid = "fixture-uuid-1111-2222-333344445555"
        assertEquals(false, serialized.contains(fixtureUuid))
    }
}
