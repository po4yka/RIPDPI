package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FastestResolverCacheTest {
    private fun candidate(resolverId: String): EncryptedDnsPathCandidate =
        EncryptedDnsPathCandidate(
            resolverId = resolverId,
            resolverLabel = resolverId,
            protocol = EncryptedDnsProtocolDoh,
            host = "dns.$resolverId.test",
            port = 443,
            tlsServerName = "dns.$resolverId.test",
            bootstrapIps = listOf("1.2.3.4"),
            dohUrl = "https://dns.$resolverId.test/dns-query",
        )

    @Test
    fun `put then get returns stored candidate`() {
        val cache = FastestResolverCache()
        val c = candidate("cloudflare")
        cache.put("example.com", "scope-1", c)
        assertEquals(c, cache.get("example.com", "scope-1"))
    }

    @Test
    fun `get returns null for missing entry`() {
        val cache = FastestResolverCache()
        assertNull(cache.get("example.com", "scope-1"))
    }

    @Test
    fun `get returns null for different scope`() {
        val cache = FastestResolverCache()
        cache.put("example.com", "scope-1", candidate("cloudflare"))
        assertNull(cache.get("example.com", "scope-2"))
    }

    @Test
    fun `get returns null after TTL expiry`() {
        var now = 0L
        val cache = FastestResolverCache(ttlMs = 1_000L, clock = { now })
        cache.put("example.com", "scope-1", candidate("cloudflare"))

        now = 500L
        assertEquals(candidate("cloudflare"), cache.get("example.com", "scope-1"))

        now = 1_001L
        assertNull(cache.get("example.com", "scope-1"))
    }

    @Test
    fun `invalidate by host and scope removes entry`() {
        val cache = FastestResolverCache()
        cache.put("example.com", "scope-1", candidate("cloudflare"))
        cache.put("example.com", "scope-2", candidate("google"))

        cache.invalidate("example.com", "scope-1")

        assertNull(cache.get("example.com", "scope-1"))
        assertEquals(candidate("google"), cache.get("example.com", "scope-2"))
    }

    @Test
    fun `invalidate by host only removes all scopes for that host`() {
        val cache = FastestResolverCache()
        cache.put("example.com", "scope-1", candidate("cloudflare"))
        cache.put("example.com", "scope-2", candidate("google"))
        cache.put("other.com", "scope-1", candidate("adguard"))

        cache.invalidate("example.com")

        assertNull(cache.get("example.com", "scope-1"))
        assertNull(cache.get("example.com", "scope-2"))
        assertEquals(candidate("adguard"), cache.get("other.com", "scope-1"))
    }

    @Test
    fun `invalidateScope removes all hosts for that scope`() {
        val cache = FastestResolverCache()
        cache.put("a.com", "scope-1", candidate("cloudflare"))
        cache.put("b.com", "scope-1", candidate("google"))
        cache.put("a.com", "scope-2", candidate("adguard"))

        cache.invalidateScope("scope-1")

        assertNull(cache.get("a.com", "scope-1"))
        assertNull(cache.get("b.com", "scope-1"))
        assertEquals(candidate("adguard"), cache.get("a.com", "scope-2"))
    }

    @Test
    fun `invalidateAll clears every entry`() {
        val cache = FastestResolverCache()
        cache.put("a.com", "scope-1", candidate("cloudflare"))
        cache.put("b.com", "scope-2", candidate("google"))

        cache.invalidateAll()

        assertNull(cache.get("a.com", "scope-1"))
        assertNull(cache.get("b.com", "scope-2"))
        assertEquals(0, cache.size())
    }

    @Test
    fun `size returns count of non-expired entries`() {
        var now = 0L
        val cache = FastestResolverCache(ttlMs = 1_000L, clock = { now })
        cache.put("a.com", "scope-1", candidate("cloudflare"))
        cache.put("b.com", "scope-1", candidate("google"))
        assertEquals(2, cache.size())

        now = 1_001L
        assertEquals(0, cache.size())
    }

    @Test
    fun `put overwrites existing entry for same key`() {
        val cache = FastestResolverCache()
        cache.put("example.com", "scope-1", candidate("cloudflare"))
        cache.put("example.com", "scope-1", candidate("google"))
        assertEquals(candidate("google"), cache.get("example.com", "scope-1"))
    }
}
