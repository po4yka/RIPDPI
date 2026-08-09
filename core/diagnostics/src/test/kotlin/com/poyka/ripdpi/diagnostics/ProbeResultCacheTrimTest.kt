package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProbeResultCacheTrimTest {
    private fun newCache(): DefaultProbeResultCache =
        DefaultProbeResultCache(
            context = RuntimeEnvironment.getApplication(),
            json = Json { ignoreUnknownKeys = true },
        )

    private fun outcome(hash: String) =
        CachedProbeOutcome(
            fingerprintHash = hash,
            headline = "headline",
            summary = "summary",
            appliedSettings = emptyList(),
            completedStageCount = 1,
            failedStageCount = 0,
            cachedAtMs = System.currentTimeMillis(),
        )

    @Test
    fun `trimToBackground drops in-memory state but the entry reloads from disk`() =
        runBlocking {
            val cache = newCache()
            cache.store(outcome("fp-1"))

            // Shedding the in-memory map must not lose the persisted entry.
            cache.trimToBackground()

            val reloaded = cache.lookup("fp-1")
            assertNotNull("entry should reload from disk after trim", reloaded)
            assertEquals("fp-1", reloaded?.fingerprintHash)
        }

    @Test
    fun `trimToBackground before any load is safe`() {
        // Must not throw when there is nothing loaded yet.
        newCache().trimToBackground()
    }

    @Test
    fun `snapshot exposes persisted outcomes for local baseline aggregation`() =
        runBlocking {
            val cache = newCache()
            cache.clear()
            cache.store(outcome("fp-baseline"))

            assertEquals(listOf("fp-baseline"), cache.snapshot().map(CachedProbeOutcome::fingerprintHash))
        }

    @Test
    fun `legacy cache entry does not invent a total stage count`() {
        val legacyJson =
            """
            {
              "fingerprintHash": "legacy",
              "headline": "headline",
              "summary": "summary",
              "appliedSettings": [],
              "completedStageCount": 5,
              "failedStageCount": 1,
              "cachedAtMs": 1
            }
            """.trimIndent()

        val decoded = Json.decodeFromString<CachedProbeOutcome>(legacyJson)

        assertNull(decoded.totalStageCount)
    }
}
