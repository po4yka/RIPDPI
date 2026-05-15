package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.CacheDegradation
import com.poyka.ripdpi.data.CacheEnvelope
import com.poyka.ripdpi.data.CacheResult
import com.poyka.ripdpi.data.CacheSource
import com.poyka.ripdpi.data.telemetryCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheDegradationTest {
    // ── Missing ────────────────────────────────────────────────────────────────

    @Test
    fun `Missing is a singleton object`() {
        val a: CacheDegradation = CacheDegradation.Missing
        val b: CacheDegradation = CacheDegradation.Missing
        assertEquals(a, b)
    }

    @Test
    fun `Missing telemetryCode is missing`() {
        assertEquals("missing", CacheDegradation.Missing.telemetryCode)
    }

    @Test
    fun `CacheResult Degraded with Missing carries correct reason`() {
        val result: CacheResult<String> =
            CacheResult.Degraded(reason = CacheDegradation.Missing)

        assertTrue(result is CacheResult.Degraded)
        assertEquals(CacheDegradation.Missing, (result as CacheResult.Degraded).reason)
    }

    // ── SchemaMismatch ─────────────────────────────────────────────────────────

    @Test
    fun `SchemaMismatch carries stored and expected versions`() {
        val degradation = CacheDegradation.SchemaMismatch(storedVersion = 3, expectedVersion = 1)

        assertEquals(3, degradation.storedVersion)
        assertEquals(1, degradation.expectedVersion)
    }

    @Test
    fun `SchemaMismatch telemetryCode is schema_mismatch`() {
        val degradation = CacheDegradation.SchemaMismatch(storedVersion = 2, expectedVersion = 1)

        assertEquals("schema_mismatch", degradation.telemetryCode)
    }

    @Test
    fun `SchemaMismatch equality is value-based`() {
        val a = CacheDegradation.SchemaMismatch(storedVersion = 5, expectedVersion = 1)
        val b = CacheDegradation.SchemaMismatch(storedVersion = 5, expectedVersion = 1)
        val c = CacheDegradation.SchemaMismatch(storedVersion = 6, expectedVersion = 1)

        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    // ── SignatureInvalid ───────────────────────────────────────────────────────

    @Test
    fun `SignatureInvalid carries detail message`() {
        val detail = "key-id mismatch: expected ripdpi-v1, got ripdpi-v2"
        val degradation = CacheDegradation.SignatureInvalid(detail = detail)

        assertEquals(detail, degradation.detail)
    }

    @Test
    fun `SignatureInvalid telemetryCode is signature_invalid`() {
        val degradation = CacheDegradation.SignatureInvalid(detail = "bad sig")

        assertEquals("signature_invalid", degradation.telemetryCode)
    }

    // ── Corrupt ────────────────────────────────────────────────────────────────

    @Test
    fun `Corrupt carries reason string`() {
        val reason = "Unexpected JSON token at offset 42"
        val degradation = CacheDegradation.Corrupt(reason = reason)

        assertEquals(reason, degradation.reason)
    }

    @Test
    fun `Corrupt telemetryCode is corrupt`() {
        val degradation = CacheDegradation.Corrupt(reason = "truncated")

        assertEquals("corrupt", degradation.telemetryCode)
    }

    // ── Exhaustive when coverage ───────────────────────────────────────────────

    @Test
    fun `all CacheDegradation subtypes produce distinct telemetry codes`() {
        val codes =
            listOf(
                CacheDegradation.Missing,
                CacheDegradation.SchemaMismatch(2, 1),
                CacheDegradation.SignatureInvalid("x"),
                CacheDegradation.Corrupt("y"),
            ).map { it.telemetryCode }

        assertEquals(4, codes.distinct().size)
    }

    // ── Parse-failure → CacheResult mapping ───────────────────────────────────

    @Test
    fun `absent cache file maps to Missing degradation`() {
        val result: CacheResult<String> = parseCacheFile(fileExists = false, content = null)

        assertTrue(result is CacheResult.Degraded)
        assertEquals(CacheDegradation.Missing, (result as CacheResult.Degraded).reason)
    }

    @Test
    fun `malformed JSON cache maps to Corrupt degradation`() {
        val result: CacheResult<String> = parseCacheFile(fileExists = true, content = "{broken json")

        assertTrue(result is CacheResult.Degraded)
        val reason = (result as CacheResult.Degraded).reason
        assertTrue(reason is CacheDegradation.Corrupt)
    }

    @Test
    fun `short content cache maps to Corrupt degradation`() {
        val result: CacheResult<String> = parseCacheFile(fileExists = true, content = "x")

        assertTrue(result is CacheResult.Degraded)
        assertTrue((result as CacheResult.Degraded).reason is CacheDegradation.Corrupt)
    }

    @Test
    fun `valid JSON cache maps to Success`() {
        val result: CacheResult<String> =
            parseCacheFile(fileExists = true, content = """{"value":"ok"}""")

        assertTrue(result is CacheResult.Success)
        assertEquals("ok", (result as CacheResult.Success).value)
    }

    @Test
    fun `future schema version maps to SchemaMismatch degradation`() {
        val result: CacheResult<String> =
            parseCacheFileWithVersion(storedVersion = 99, currentVersion = 1)

        assertTrue(result is CacheResult.Degraded)
        val reason = (result as CacheResult.Degraded).reason
        assertTrue(reason is CacheDegradation.SchemaMismatch)
        assertEquals(99, (reason as CacheDegradation.SchemaMismatch).storedVersion)
        assertEquals(1, reason.expectedVersion)
    }
}

// ── Minimal parse helpers used only in this test ──────────────────────────────

private data class SimplePayload(
    val value: String,
)

/** Simulates reading a cache file and producing a [CacheResult]. */
private fun parseCacheFile(
    fileExists: Boolean,
    content: String?,
): CacheResult<String> {
    if (!fileExists || content == null) {
        return CacheResult.Degraded(reason = CacheDegradation.Missing)
    }
    return runCatching {
        // Naive JSON extraction: expects {"value":"<something>"}
        val match =
            Regex(""""value"\s*:\s*"([^"]+)"""").find(content)
                ?: error("no value field")
        val parsed = match.groupValues[1]
        val envelope =
            CacheEnvelope(
                schemaVersion = 1,
                storedAt = 0L,
                source = CacheSource.Bundled,
                payload = parsed,
            )
        CacheResult.Success(value = parsed, envelope = envelope)
    }.getOrElse { error ->
        CacheResult.Degraded(reason = CacheDegradation.Corrupt(error.message ?: "parse error"))
    }
}

private fun parseCacheFileWithVersion(
    storedVersion: Int,
    currentVersion: Int,
): CacheResult<String> {
    if (storedVersion > currentVersion) {
        return CacheResult.Degraded(
            reason =
                CacheDegradation.SchemaMismatch(
                    storedVersion = storedVersion,
                    expectedVersion = currentVersion,
                ),
        )
    }
    val envelope =
        CacheEnvelope(
            schemaVersion = storedVersion,
            storedAt = 0L,
            source = CacheSource.Bundled,
            payload = "value",
        )
    return CacheResult.Success(value = "value", envelope = envelope)
}
