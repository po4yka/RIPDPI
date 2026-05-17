package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD coverage for the NaiveProxy `--probe` JSON line parser.
 *
 * The native helper at `native/rust/crates/ripdpi-naiveproxy/src/main.rs`
 * emits a single `RIPDPI-PROBE { ... }` line on `--probe` exit carrying
 * `schema_version`, `helper_version`, and `features`. The Kotlin
 * service-side manager parses this line at every helper start so a
 * schema-version mismatch refuses launch with a typed failure rather
 * than waiting for the helper to crash on an unsupported flag.
 *
 * Tracks the second half of
 * `docs/tasks/issues/make-naiveproxy-helper-probe-return-structured-version-json.md`.
 */
class NaiveProxyProbeParserTest {
    @Test
    fun `parses a well-formed probe line into all fields`() {
        val line =
            "RIPDPI-PROBE " +
                """{"schema_version":1,"helper_version":"0.1.0","features":[""" +
                """"socks5-listener","https-connect-upstream"]}"""

        val probe = NaiveProxyProbeParser.parse(line)

        assertNotNull(probe)
        probe!!
        assertEquals(1, probe.schemaVersion)
        assertEquals("0.1.0", probe.helperVersion)
        assertEquals(listOf("socks5-listener", "https-connect-upstream"), probe.features)
    }

    @Test
    fun `returns null when the line is missing the RIPDPI-PROBE marker`() {
        val line = """{"schema_version":1,"helper_version":"0.1.0","features":[]}"""
        assertNull(NaiveProxyProbeParser.parse(line))
    }

    @Test
    fun `returns null on malformed json after the marker`() {
        val line = "RIPDPI-PROBE not-json"
        assertNull(NaiveProxyProbeParser.parse(line))
    }

    @Test
    fun `returns null when schema_version is missing`() {
        val line = """RIPDPI-PROBE {"helper_version":"0.1.0","features":[]}"""
        assertNull(NaiveProxyProbeParser.parse(line))
    }

    @Test
    fun `returns null when helper_version is missing`() {
        val line = """RIPDPI-PROBE {"schema_version":1,"features":[]}"""
        assertNull(NaiveProxyProbeParser.parse(line))
    }

    @Test
    fun `accepts an empty features array`() {
        val line = """RIPDPI-PROBE {"schema_version":1,"helper_version":"0.0.0","features":[]}"""
        val probe = NaiveProxyProbeParser.parse(line)
        assertNotNull(probe)
        assertEquals(emptyList<String>(), probe!!.features)
    }

    @Test
    fun `schema-version mismatch refuses launch via isSchemaSupported`() {
        val probe = NaiveProxyProbe(schemaVersion = 99, helperVersion = "0.1.0", features = emptyList())
        assertTrue(!probe.isSchemaSupported(supportedRange = 1..1))
        assertTrue(probe.isSchemaSupported(supportedRange = 1..99))
    }
}
