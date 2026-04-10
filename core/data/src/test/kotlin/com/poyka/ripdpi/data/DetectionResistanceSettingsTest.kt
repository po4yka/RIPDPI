package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectionResistanceSettingsTest {
    @Test
    fun `native default fingerprint migrates to chrome stable`() {
        assertEquals(TlsFingerprintProfileChromeStable, normalizeTlsFingerprintProfile("native_default"))
    }

    @Test
    fun `unknown fingerprint falls back to chrome stable`() {
        assertEquals(TlsFingerprintProfileChromeStable, normalizeTlsFingerprintProfile("mystery_profile"))
    }

    @Test
    fun `firefox fingerprint remains explicit`() {
        assertEquals(
            TlsFingerprintProfileFirefoxStable,
            normalizeTlsFingerprintProfile(TlsFingerprintProfileFirefoxStable),
        )
    }

    @Test
    fun `fingerprint summary reports normalized label`() {
        assertEquals("Chrome stable", tlsFingerprintProfileSummary("native_default"))
        assertEquals("Firefox stable", tlsFingerprintProfileSummary(TlsFingerprintProfileFirefoxStable))
    }
}
