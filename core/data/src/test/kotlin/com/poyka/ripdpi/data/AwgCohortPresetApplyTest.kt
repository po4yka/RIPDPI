package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.AwgProfileForm
import com.poyka.ripdpi.data.awg.applyCohortPreset
import com.poyka.ripdpi.data.awg.decodeAwgCohortCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Applying a cohort preset to a profile form must rewrite exactly the
 * obfuscation fields and leave server / port / keys untouched.
 */
class AwgCohortPresetApplyTest {
    private fun shippedCatalogJson(): String =
        javaClass.classLoader!!
            .getResourceAsStream("awg-cohorts.json")!!
            .bufferedReader()
            .use { it.readText() }

    private val baseForm =
        AwgProfileForm(
            server = "awg.example.com",
            serverPort = 51820,
            interfacePrivateKey = "cHJpdmF0ZS1rZXktZml4dHVyZS1iYXNlLWZvcm0=",
            peerPublicKey = "cHVibGljLWtleS1maXh0dXJlLWJhc2UtZm9ybQ==",
            presharedKey = "cHJlc2hhcmVkLWtleS1maXh0dXJlLWJhc2U=",
            // Pre-existing obfuscation values that a preset must overwrite.
            jc = 99,
            jmin = 99,
            jmax = 99,
            s1 = 99,
            s2 = 99,
            h1 = 99L,
            h2 = 99L,
            h3 = 99L,
            h4 = 99L,
            cohortId = "Custom",
        )

    @Test
    fun `applying rtk_south rewrites only obfuscation fields`() {
        val catalog = decodeAwgCohortCatalog(shippedCatalogJson())
        val rtk = catalog.find("rtk_south")!!

        val applied = applyCohortPreset(baseForm, rtk)

        // Obfuscation fields rewritten to the preset values.
        assertEquals(4, applied.jc)
        assertEquals(10, applied.jmin)
        assertEquals(50, applied.jmax)
        assertEquals(0, applied.s1)
        assertEquals(0, applied.s2)
        assertEquals(1L, applied.h1)
        assertEquals(2L, applied.h2)
        assertEquals(3L, applied.h3)
        assertEquals(4L, applied.h4)
        assertEquals("rtk_south", applied.cohortId)

        // Server / port / keys untouched.
        assertEquals(baseForm.server, applied.server)
        assertEquals(baseForm.serverPort, applied.serverPort)
        assertEquals(baseForm.interfacePrivateKey, applied.interfacePrivateKey)
        assertEquals(baseForm.peerPublicKey, applied.peerPublicKey)
        assertEquals(baseForm.presharedKey, applied.presharedKey)
    }

    @Test
    fun `applying every shipped preset leaves server, port and keys untouched`() {
        val catalog = decodeAwgCohortCatalog(shippedCatalogJson())

        catalog.presets.forEach { preset ->
            val applied = applyCohortPreset(baseForm, preset)

            assertEquals("server for ${preset.id}", baseForm.server, applied.server)
            assertEquals("port for ${preset.id}", baseForm.serverPort, applied.serverPort)
            assertEquals(
                "private key for ${preset.id}",
                baseForm.interfacePrivateKey,
                applied.interfacePrivateKey,
            )
            assertEquals("public key for ${preset.id}", baseForm.peerPublicKey, applied.peerPublicKey)
            assertEquals("preshared key for ${preset.id}", baseForm.presharedKey, applied.presharedKey)

            // Obfuscation fields take the preset numbers.
            assertEquals("jc for ${preset.id}", preset.jc, applied.jc)
            assertEquals("jmin for ${preset.id}", preset.jmin, applied.jmin)
            assertEquals("jmax for ${preset.id}", preset.jmax, applied.jmax)
            assertEquals("s1 for ${preset.id}", preset.s1, applied.s1)
            assertEquals("s2 for ${preset.id}", preset.s2, applied.s2)
            assertEquals("cohortId for ${preset.id}", preset.id, applied.cohortId)
        }
    }

    @Test
    fun `applying a randomized-header preset still pins concrete H values from the catalog`() {
        // The catalog ships concrete H values even for randomized cohorts; the
        // apply step is deterministic — re-randomization is a separate concern
        // out of scope here. The point: applied H values equal the catalog's.
        val catalog = decodeAwgCohortCatalog(shippedCatalogJson())
        val default = catalog.find("default")!!

        val applied = applyCohortPreset(baseForm, default)

        assertEquals(default.h1, applied.h1)
        assertEquals(default.h2, applied.h2)
        assertEquals(default.h3, applied.h3)
        assertEquals(default.h4, applied.h4)
    }
}
