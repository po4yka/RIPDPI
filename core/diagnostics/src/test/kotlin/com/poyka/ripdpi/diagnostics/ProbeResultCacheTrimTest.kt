package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
