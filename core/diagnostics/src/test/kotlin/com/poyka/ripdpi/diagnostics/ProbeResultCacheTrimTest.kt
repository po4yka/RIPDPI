package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 35])
class ProbeResultCacheTrimTest {
    private val cacheFile: File
        get() = File(RuntimeEnvironment.getApplication().filesDir, "probe_result_cache.json")

    @Before
    fun removeOwnedCacheFiles() {
        for (suffix in listOf("", ".bak", ".new")) {
            File(cacheFile.path + suffix).deleteRecursively()
        }
    }

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
    fun `backup-only cache recovers after interrupted replacement`() =
        runBlocking {
            val expected = outcome("old")
            newCache().store(expected)
            assertTrue(cacheFile.renameTo(File(cacheFile.path + ".bak")))
            assertEquals(expected, newCache().lookup("old"))
        }

    @Test
    @Config(sdk = [35])
    fun `partial new file does not replace complete cache`() =
        runBlocking {
            val expected = outcome("old")
            newCache().store(expected)
            File(cacheFile.path + ".new").writeText("{")
            assertEquals(expected, newCache().lookup("old"))
        }

    @Test
    @Config(sdk = [35])
    fun `failed write keeps previous complete cache`() =
        runBlocking {
            val cache = newCache()
            val expected = outcome("old")
            cache.store(expected)
            val blocked = File(cacheFile.path + ".new")
            assertTrue(blocked.mkdir())
            File(blocked, "blocker").writeText("block replacement")
            cache.store(outcome("new"))
            assertTrue(blocked.deleteRecursively())
            assertEquals(expected, newCache().lookup("old"))
            assertNull(newCache().lookup("new"))
        }

    @Test
    fun `completed replacement and clear survive reopen`() =
        runBlocking {
            val expected = outcome("same").copy(headline = "complete replacement", completedStageCount = 4)
            val cache = newCache()
            cache.store(outcome("same"))
            cache.store(expected)
            assertEquals(expected, newCache().lookup("same"))
            newCache().clear()
            assertNull(newCache().lookup("same"))
        }

    @Test
    fun `corrupt cache falls back and accepts new state`() =
        runBlocking {
            cacheFile.writeText("{invalid")
            val cache = newCache()
            assertNull(cache.lookup("missing"))
            val expected = outcome("valid")
            cache.store(expected)
            assertEquals(expected, newCache().lookup("valid"))
        }

    @Test
    fun `expired entries stay unavailable after reopen`() =
        runBlocking {
            val cache = newCache()
            cache.store(
                outcome("expired").copy(cachedAtMs = System.currentTimeMillis() - 24L * 60 * 60 * 1000 - 60_000),
            )
            assertNull(cache.lookup("expired"))
            assertNull(newCache().lookup("expired"))
        }

    @Test
    fun `eleventh entry evicts oldest and explicit eviction survives reopen`() =
        runBlocking {
            val cache = newCache()
            val start = System.currentTimeMillis() - 60_000
            val values = (0..10).map { outcome("fp-$it").copy(cachedAtMs = start + it * 1000) }
            values.forEach { cache.store(it) }
            val reopened = newCache()
            assertNull(reopened.lookup("fp-0"))
            values.drop(1).forEach { assertEquals(it, reopened.lookup(it.fingerprintHash)) }
            reopened.evict("fp-1")
            assertNull(newCache().lookup("fp-1"))
        }
}
