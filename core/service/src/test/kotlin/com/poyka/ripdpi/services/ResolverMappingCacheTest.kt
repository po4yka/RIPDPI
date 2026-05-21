package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DnsMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolverMappingCacheTest {
    @Test
    fun `cache hits within ttl`() {
        var now = 1_000L
        val cache = ResolverMappingCache { now }
        val mapping = ResolvedMapping("203.0.113.10", ResolvedIpFamily.IPV4, DnsMode.DOH_PRIMARY)

        cache.put("Example.Org.", "wifi:home", mapping, ttlMs = 1_000L)
        now = 1_999L

        assertEquals(mapping, cache.get("example.org", "wifi:home"))
    }

    @Test
    fun `cache expires after ttl`() {
        var now = 1_000L
        val cache = ResolverMappingCache { now }
        val mapping = ResolvedMapping("203.0.113.10", ResolvedIpFamily.IPV4, DnsMode.DOH_PRIMARY)

        cache.put("example.org", "wifi:home", mapping, ttlMs = 1_000L)
        now = 2_000L

        assertNull(cache.get("example.org", "wifi:home"))
    }

    @Test
    fun `cache misses across network fingerprints`() {
        val cache = ResolverMappingCache { 1_000L }
        val mapping = ResolvedMapping("203.0.113.10", ResolvedIpFamily.IPV4, DnsMode.DOH_PRIMARY)

        cache.put("example.org", "wifi:home", mapping, ttlMs = 1_000L)

        assertNull(cache.get("example.org", "cell:carrier"))
    }
}
