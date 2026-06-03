package com.poyka.ripdpi.platform

import com.poyka.ripdpi.data.TrimmableCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTrimCoordinatorTest {
    private class RecordingCache : TrimmableCache {
        var trimCount = 0
            private set

        override fun trimToBackground() {
            trimCount++
        }
    }

    private class ThrowingCache : TrimmableCache {
        var trimmed = false
            private set

        override fun trimToBackground() {
            trimmed = true
            error("boom")
        }
    }

    @Test
    fun `onAppBackgrounded trims every registered cache`() {
        val a = RecordingCache()
        val b = RecordingCache()
        val coordinator = MemoryTrimCoordinator(setOf(a, b))

        coordinator.onAppBackgrounded()

        assertEquals(1, a.trimCount)
        assertEquals(1, b.trimCount)
    }

    @Test
    fun `a throwing cache does not stop the others from being trimmed`() {
        val throwing = ThrowingCache()
        val healthy = RecordingCache()
        // Order the throwing cache first so we prove the failure is isolated.
        val coordinator = MemoryTrimCoordinator(linkedSetOf<TrimmableCache>(throwing, healthy))

        coordinator.onAppBackgrounded()

        assertTrue(throwing.trimmed)
        assertEquals(1, healthy.trimCount)
    }

    @Test
    fun `onAppBackgrounded is a no-op with no registered caches`() {
        // Must not throw when the multibound set is empty.
        MemoryTrimCoordinator(emptySet()).onAppBackgrounded()
    }
}
