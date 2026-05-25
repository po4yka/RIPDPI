package com.poyka.ripdpi.diagnostics.replay

import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReplayResultStoreTest {
    private fun makeResult(domain: String): ReplayProbeResult =
        ReplayProbeResult(
            request = ReplayProbeRequest(domain = domain, strategyId = "default", timeoutMs = 1_000),
            events = persistentListOf(),
            verdict = ReplayVerdict.Success,
            terminalStep = null,
            recommendationKey = "",
        )

    @Test
    fun recent_isEmpty_byDefault() {
        val store = ReplayResultStore()
        assertEquals(emptyList<ReplayProbeResult>(), store.recent())
    }

    @Test
    fun record_appendsInFifoOrder() {
        val store = ReplayResultStore()
        repeat(3) { i -> store.record(makeResult("d$i.test")) }
        assertEquals(
            listOf("d0.test", "d1.test", "d2.test"),
            store.recent().map { it.request.domain },
        )
    }

    @Test
    fun record_evictsOldestPastMaxEntries() {
        val store = ReplayResultStore()
        repeat(ReplayResultStore.MaxEntries + 3) { i -> store.record(makeResult("d$i.test")) }
        val recent = store.recent()
        assertEquals(ReplayResultStore.MaxEntries, recent.size)
        // First three (d0, d1, d2) evicted; ring should start at d3.
        assertEquals("d3.test", recent.first().request.domain)
        assertEquals(
            "d${ReplayResultStore.MaxEntries + 2}.test",
            recent.last().request.domain,
        )
    }

    @Test
    fun clear_emptiesRing() {
        val store = ReplayResultStore()
        repeat(5) { i -> store.record(makeResult("d$i.test")) }
        store.clear()
        assertEquals(emptyList<ReplayProbeResult>(), store.recent())
    }

    @Test
    fun record_isThreadSafe_underContention() {
        val store = ReplayResultStore()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val total = 1_000
        val done = CountDownLatch(total)
        try {
            repeat(total) { i ->
                pool.execute {
                    start.await()
                    store.record(makeResult("d$i.test"))
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
        // No data corruption: size is capped at MaxEntries, all entries
        // are non-null and well-formed (no torn writes).
        val recent = store.recent()
        assertEquals(ReplayResultStore.MaxEntries, recent.size)
        recent.forEach { result ->
            assertTrue(result.request.domain.startsWith("d") && result.request.domain.endsWith(".test"))
        }
    }
}
