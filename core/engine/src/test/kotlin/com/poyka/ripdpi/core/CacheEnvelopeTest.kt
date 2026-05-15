package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.CacheEnvelope
import com.poyka.ripdpi.data.CacheResult
import com.poyka.ripdpi.data.CacheSource
import com.poyka.ripdpi.data.withFallback
import com.poyka.ripdpi.data.withFallbackOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CacheEnvelopeTest {
    @Test
    fun `envelope round-trip preserves all fields`() {
        val envelope =
            CacheEnvelope(
                schemaVersion = 1,
                storedAt = 1_700_000_000_000L,
                source = CacheSource.Fetched,
                payload = "hello",
            )

        assertEquals(1, envelope.schemaVersion)
        assertEquals(1_700_000_000_000L, envelope.storedAt)
        assertEquals(CacheSource.Fetched, envelope.source)
        assertEquals("hello", envelope.payload)
    }

    @Test
    fun `CacheSource wire values are stable`() {
        assertEquals("bundled", CacheSource.Bundled.wireValue)
        assertEquals("fetched", CacheSource.Fetched.wireValue)
    }

    @Test
    fun `CacheResult Success returns value via withFallback`() {
        val envelope =
            CacheEnvelope(
                schemaVersion = 1,
                storedAt = 0L,
                source = CacheSource.Bundled,
                payload = 42,
            )
        val result: CacheResult<Int> = CacheResult.Success(value = 42, envelope = envelope)

        assertEquals(42, result.withFallback(0))
    }

    @Test
    fun `CacheResult Degraded returns default via withFallback`() {
        val result: CacheResult<Int> =
            CacheResult.Degraded(
                reason = com.poyka.ripdpi.data.CacheDegradation.Missing,
            )

        assertEquals(99, result.withFallback(99))
    }

    @Test
    fun `CacheResult Success returns value via withFallbackOrNull`() {
        val envelope =
            CacheEnvelope(
                schemaVersion = 1,
                storedAt = 0L,
                source = CacheSource.Bundled,
                payload = "ok",
            )
        val result: CacheResult<String> = CacheResult.Success(value = "ok", envelope = envelope)

        assertEquals("ok", result.withFallbackOrNull())
    }

    @Test
    fun `CacheResult Degraded returns null via withFallbackOrNull`() {
        val result: CacheResult<String> =
            CacheResult.Degraded(
                reason =
                    com.poyka.ripdpi.data.CacheDegradation
                        .Corrupt("bad json"),
            )

        assertNull(result.withFallbackOrNull())
    }

    @Test
    fun `envelope equality is value-based`() {
        val a =
            CacheEnvelope(
                schemaVersion = 2,
                storedAt = 1_000L,
                source = CacheSource.Fetched,
                payload = listOf(1, 2, 3),
            )
        val b =
            CacheEnvelope(
                schemaVersion = 2,
                storedAt = 1_000L,
                source = CacheSource.Fetched,
                payload = listOf(1, 2, 3),
            )

        assertEquals(a, b)
    }
}
