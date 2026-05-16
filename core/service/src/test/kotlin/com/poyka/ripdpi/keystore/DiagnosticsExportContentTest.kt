package com.poyka.ripdpi.keystore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsExportContentTest {
    private val redactor = ProfileDiagnosticsRedactor()

    @Test
    fun `redacted bundle uuid is replaced with redacted string`() {
        val bundle =
            redactor.redactedBundle(
                configHash = "sha256-fixture-config-hash-1",
                uuid = "fixture-uuid-export-1",
            )
        assertEquals("redacted", bundle.uuid)
    }

    @Test
    fun `redacted bundle does not contain raw uuid`() {
        val bundle =
            redactor.redactedBundle(
                configHash = "sha256-fixture-config-hash-2",
                uuid = "fixture-uuid-export-2",
            )
        assertFalse(bundle.toString().contains("fixture-uuid-export-2"))
    }

    @Test
    fun `redacted bundle password is replaced with redacted string`() {
        val bundle =
            redactor.redactedBundle(
                configHash = "sha256-fixture-config-hash-3",
                password = "fixture-password-export-1",
            )
        assertEquals("redacted", bundle.password)
    }

    @Test
    fun `redacted bundle does not contain raw password`() {
        val bundle =
            redactor.redactedBundle(
                configHash = "sha256-fixture-config-hash-4",
                password = "fixture-password-export-2",
            )
        assertFalse(bundle.toString().contains("fixture-password-export-2"))
    }

    @Test
    fun `redacted bundle subscription token is replaced with redacted string`() {
        val bundle =
            redactor.redactedBundle(
                configHash = "sha256-fixture-config-hash-5",
                subscriptionToken = "fixture-token-export-1",
            )
        assertEquals("redacted", bundle.subscriptionToken)
    }

    @Test
    fun `redacted bundle does not contain raw subscription token`() {
        val bundle =
            redactor.redactedBundle(
                configHash = "sha256-fixture-config-hash-6",
                subscriptionToken = "fixture-token-export-2",
            )
        assertFalse(bundle.toString().contains("fixture-token-export-2"))
    }

    @Test
    fun `configHash is preserved verbatim in safeLabel field`() {
        val hash = "sha256-fixture-config-hash-7"
        val bundle = redactor.redactedBundle(configHash = hash)
        assertEquals(hash, bundle.safeLabel)
    }

    @Test
    fun `null sensitive fields remain null after redaction`() {
        val bundle = redactor.redactedBundle(configHash = "sha256-fixture-config-hash-8")
        assertNull(bundle.uuid)
        assertNull(bundle.password)
        assertNull(bundle.subscriptionToken)
        assertNull(bundle.shortId)
        assertNull(bundle.endpoint)
    }

    @Test
    fun `redacted bundle endpoint is replaced with redacted string`() {
        val bundle =
            redactor.redactedBundle(
                configHash = "sha256-fixture-config-hash-9",
                endpoint = "fixture-endpoint-vpn.example.com:443",
            )
        assertEquals("redacted", bundle.endpoint)
    }

    @Test
    fun `all sensitive fields are redacted simultaneously`() {
        val bundle =
            redactor.redactedBundle(
                configHash = "sha256-fixture-config-hash-10",
                uuid = "fixture-uuid-export-3",
                shortId = "fixture-short-id-export-1",
                password = "fixture-password-export-3",
                subscriptionToken = "fixture-token-export-3",
                endpoint = "fixture-endpoint-export-1.example.com",
            )
        assertEquals("redacted", bundle.uuid)
        assertEquals("redacted", bundle.shortId)
        assertEquals("redacted", bundle.password)
        assertEquals("redacted", bundle.subscriptionToken)
        assertEquals("redacted", bundle.endpoint)
        assertNotNull(bundle.safeLabel)
    }
}
