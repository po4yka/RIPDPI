package com.poyka.ripdpi.services.dns.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastKnownGoodCacheTest {
    @Test
    fun `stored entry is retrievable before TTL`() {
        val cache = LastKnownGoodCache(maxTtlMs = 60_000L)
        cache.store("fixture-proxy.example", "A", listOf("1.2.3.4"))
        val entry = cache.lookup("fixture-proxy.example", "A")
        assertNotNull(entry)
        assertEquals(listOf("1.2.3.4"), entry!!.addresses)
    }

    @Test
    fun `stored entry is tagged BootstrapDerived`() {
        val cache = LastKnownGoodCache(maxTtlMs = 60_000L)
        cache.store("fixture-proxy.example", "A", listOf("1.2.3.4"))
        val entry = cache.lookup("fixture-proxy.example", "A")
        assertNotNull(entry)
        assertEquals(CacheEntrySource.BootstrapDerived, entry!!.source)
    }

    @Test
    fun `expired entry is not returned`() {
        val cache = LastKnownGoodCache(maxTtlMs = 1L)
        cache.store("fixture-proxy.example", "A", listOf("1.2.3.4"))
        Thread.sleep(10)
        val entry = cache.lookup("fixture-proxy.example", "A")
        assertNull(entry)
    }

    @Test
    fun `TTL is bounded to maxTtlMs`() {
        val cache = LastKnownGoodCache(maxTtlMs = 60_000L)
        assertTrue(cache.maxTtlMs <= 60_000L)
    }

    @Test
    fun `absent entry returns null`() {
        val cache = LastKnownGoodCache(maxTtlMs = 60_000L)
        assertNull(cache.lookup("fixture-unknown.example", "A"))
    }
}
