package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.AwgCohortCatalog
import com.poyka.ripdpi.data.awg.AwgCohortPreset
import com.poyka.ripdpi.data.awg.AwgProfileForm
import com.poyka.ripdpi.data.awg.applyCohortPreset
import com.poyka.ripdpi.data.awg.decodeAwgCohortCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loads the shipped `awg-cohorts.json` asset and asserts the six presets parse
 * and validate against the AmneziaWG config model. Also covers the
 * malformed-JSON degradation path: the catalog must fall back to empty rather
 * than propagating a parse exception.
 */
class AwgCohortCatalogLoadTest {
    /** The production asset, read from the test classpath copy. */
    private fun shippedCatalogJson(): String {
        val stream =
            javaClass.classLoader?.getResourceAsStream("awg-cohorts.json")
                ?: error("awg-cohorts.json missing from test resources")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun `shipped catalog parses the six expected presets`() {
        val catalog = decodeAwgCohortCatalog(shippedCatalogJson())

        assertEquals(
            listOf("default", "rtk_south", "mts", "beeline", "megafon", "home_isp_broad"),
            catalog.presets.map { it.id },
        )
    }

    @Test
    fun `every preset carries id, displayName key, description key and the full obfuscation field set`() {
        val catalog = decodeAwgCohortCatalog(shippedCatalogJson())

        catalog.presets.forEach { preset ->
            assertTrue("id blank for ${preset.id}", preset.id.isNotBlank())
            // displayName / description are string-resource keys, never literal text.
            assertTrue("displayName key for ${preset.id}", preset.displayNameKey.startsWith("awg_cohort_"))
            assertTrue("description key for ${preset.id}", preset.descriptionKey.startsWith("awg_cohort_"))
            // Full obfuscation field set present.
            assertNotNull("jc for ${preset.id}", preset.jc)
            assertNotNull("jmin for ${preset.id}", preset.jmin)
            assertNotNull("jmax for ${preset.id}", preset.jmax)
            assertNotNull("s1 for ${preset.id}", preset.s1)
            assertNotNull("s2 for ${preset.id}", preset.s2)
            assertNotNull("h1 for ${preset.id}", preset.h1)
            assertNotNull("h2 for ${preset.id}", preset.h2)
            assertNotNull("h3 for ${preset.id}", preset.h3)
            assertNotNull("h4 for ${preset.id}", preset.h4)
        }
    }

    @Test
    fun `the asset JSON holds no literal localized text`() {
        val raw = shippedCatalogJson()
        // displayName / description must be keys; a quick guard that no obvious
        // human sentence leaked in.
        assertTrue(raw.contains("awg_cohort_"))
        assertTrue("no literal display label", !raw.contains("Rostelecom"))
    }

    @Test
    fun `default preset mirrors the deployer AWG-COHORTS values`() {
        val catalog = decodeAwgCohortCatalog(shippedCatalogJson())
        val default = catalog.find("default")!!

        assertEquals(4, default.jc)
        assertEquals(40, default.jmin)
        assertEquals(70, default.jmax)
        assertEquals(50, default.s1)
        assertEquals(100, default.s2)
        // default uses randomized headers per peer.
        assertTrue(default.randomizeHeaders)
    }

    @Test
    fun `rtk_south preset mirrors the deployer AWG-COHORTS values`() {
        val catalog = decodeAwgCohortCatalog(shippedCatalogJson())
        val rtk = catalog.find("rtk_south")!!

        assertEquals(4, rtk.jc)
        assertEquals(10, rtk.jmin)
        assertEquals(50, rtk.jmax)
        assertEquals(0, rtk.s1)
        assertEquals(0, rtk.s2)
        // rtk_south pins H1..H4 = 1,2,3,4 and does NOT randomize.
        assertEquals(1L, rtk.h1)
        assertEquals(2L, rtk.h2)
        assertEquals(3L, rtk.h3)
        assertEquals(4L, rtk.h4)
        assertTrue(!rtk.randomizeHeaders)
    }

    @Test
    fun `mobile-carrier cohorts mirror the deployer mobile baseline`() {
        val catalog = decodeAwgCohortCatalog(shippedCatalogJson())

        listOf("mts", "beeline", "megafon").forEach { id ->
            val preset = catalog.find(id)!!
            assertEquals("$id jc", 4, preset.jc)
            assertEquals("$id jmin", 40, preset.jmin)
            assertEquals("$id jmax", 70, preset.jmax)
            assertEquals("$id s1", 50, preset.s1)
            assertEquals("$id s2", 100, preset.s2)
            assertTrue("$id randomizes headers", preset.randomizeHeaders)
        }
    }

    @Test
    fun `home_isp_broad mirrors the broad-rule home ISP fallback`() {
        val catalog = decodeAwgCohortCatalog(shippedCatalogJson())
        val home = catalog.find("home_isp_broad")!!

        assertEquals(4, home.jc)
        assertEquals(40, home.jmin)
        assertEquals(70, home.jmax)
        assertEquals(50, home.s1)
        assertEquals(100, home.s2)
        assertTrue(home.randomizeHeaders)
    }

    @Test
    fun `malformed catalog JSON yields an empty catalog, no crash`() {
        val catalog = decodeAwgCohortCatalog("{ this is not valid json")

        // App boots with an empty catalog; the picker would show only "Custom".
        assertTrue(catalog.presets.isEmpty())
        assertNull(catalog.find("default"))
    }

    @Test
    fun `a preset with an unknown obfuscation key is rejected during strict validation`() {
        // strictValidate is what the build-time validator (and its test stand-in)
        // calls; it must reject an unknown key.
        val withUnknownKey =
            """
            { "presets": [
              { "id": "x", "displayNameKey": "awg_cohort_x_name", "descriptionKey": "awg_cohort_x_desc",
                "jc": 4, "jmin": 40, "jmax": 70, "s1": 50, "s2": 100,
                "h1": 1, "h2": 2, "h3": 3, "h4": 4, "randomizeHeaders": false,
                "bogusKey": 1 } ] }
            """.trimIndent()

        val errors = AwgCohortCatalog.strictValidate(withUnknownKey)
        assertTrue("expected an unknown-key error", errors.any { it.contains("bogusKey") })
    }

    @Test
    fun `a preset missing a required field is rejected during strict validation`() {
        val missingJc =
            """
            { "presets": [
              { "id": "x", "displayNameKey": "awg_cohort_x_name", "descriptionKey": "awg_cohort_x_desc",
                "jmin": 40, "jmax": 70, "s1": 50, "s2": 100,
                "h1": 1, "h2": 2, "h3": 3, "h4": 4, "randomizeHeaders": false } ] }
            """.trimIndent()

        val errors = AwgCohortCatalog.strictValidate(missingJc)
        assertTrue("expected a missing-field error", errors.any { it.contains("jc") })
    }

    @Test
    fun `the shipped catalog passes strict validation`() {
        val errors = AwgCohortCatalog.strictValidate(shippedCatalogJson())
        assertEquals(emptyList<String>(), errors)
    }

    @Test
    fun `a preset carrying safe zero s3 s4 and i1-i5 extensions strict-validates`() {
        // s3/s4 and i1..i5 are allowed (not required) keys: a preset may pin them.
        val withExtensions =
            """
            { "presets": [
              { "id": "ext", "displayNameKey": "awg_cohort_ext_name", "descriptionKey": "awg_cohort_ext_desc",
                "jc": 4, "jmin": 40, "jmax": 70, "s1": 50, "s2": 100, "s3": 0, "s4": 0,
                "h1": 1, "h2": 2, "h3": 3, "h4": 4,
                "i1": "deadbeef", "i2": "cafebabe", "i3": "0badf00d", "i4": "feedface", "i5": "8badf00d",
                "randomizeHeaders": false } ] }
            """.trimIndent()

        val errors = AwgCohortCatalog.strictValidate(withExtensions)
        assertEquals(emptyList<String>(), errors)

        val catalog = decodeAwgCohortCatalog(withExtensions)
        val ext = catalog.find("ext")!!
        assertEquals(0, ext.s3)
        assertEquals(0, ext.s4)
        assertEquals("deadbeef", ext.i1)
        assertEquals("8badf00d", ext.i5)
    }

    @Test
    fun `strict validation rejects non-zero s3 or s4`() {
        fun catalog(
            s3: Int,
            s4: Int,
        ) = """
            { "presets": [
              { "id": "unsafe", "displayNameKey": "awg_cohort_unsafe_name", "descriptionKey": "awg_cohort_unsafe_desc",
                "jc": 4, "jmin": 40, "jmax": 70, "s1": 50, "s2": 100, "s3": $s3, "s4": $s4,
                "h1": 1, "h2": 2, "h3": 3, "h4": 4, "randomizeHeaders": false } ] }
            """.trimIndent()

        val s3Errors = AwgCohortCatalog.strictValidate(catalog(s3 = 1, s4 = 0))
        val s4Errors = AwgCohortCatalog.strictValidate(catalog(s3 = 0, s4 = 1))

        assertTrue(s3Errors.any { it.contains("amneziawg-go#110") })
        assertTrue(s4Errors.any { it.contains("amneziawg-go#110") })
    }

    @Test
    fun `applyCohortPreset threads s3 s4 and i1-i5 into the form`() {
        val form =
            AwgProfileForm(
                server = "host.example.com",
                serverPort = 51820,
                interfacePrivateKey = "priv",
                peerPublicKey = "pub",
            )
        val preset =
            AwgCohortPreset(
                id = "ext",
                displayNameKey = "awg_cohort_ext_name",
                descriptionKey = "awg_cohort_ext_desc",
                jc = 4,
                jmin = 40,
                jmax = 70,
                s1 = 50,
                s2 = 100,
                s3 = 25,
                s4 = 35,
                h1 = 1,
                h2 = 2,
                h3 = 3,
                h4 = 4,
                i1 = "deadbeef",
                i5 = "8badf00d",
                randomizeHeaders = false,
            )

        val applied = applyCohortPreset(form, preset)

        assertEquals(25, applied.s3)
        assertEquals(35, applied.s4)
        assertEquals("deadbeef", applied.i1)
        assertEquals("", applied.i2)
        assertEquals("8badf00d", applied.i5)
        // Identity material is preserved.
        assertEquals("host.example.com", applied.server)
        assertEquals("ext", applied.cohortId)
    }
}
