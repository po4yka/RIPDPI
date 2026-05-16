package com.poyka.ripdpi.services.dns.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that a valid (non-expired) cache entry is served without triggering
 * strict failure or contacting the encrypted resolver transport.
 */
class CacheAssistedRecoveryTest {
    private val primary = ResolverEndpoint(EncryptedResolverKind.DohPost, "https://dns.example.com/dns-query")

    @Test
    fun `cache hit returns success without calling transport`() {
        var transportCalled = false
        val transport =
            EncryptedResolverTransport { _, _, _, _ ->
                transportCalled = true
                null
            }
        val cache = DnsCache { _, _ -> listOf("10.0.0.1") }
        val policy = FailoverPolicy(primary = primary)

        val result = StrictDnsResolver(transport, cache).resolve("proxy.example", "A", policy)

        assertFalse("Transport must not be called on cache hit", transportCalled)
        assertTrue(result is StrictDnsResult.Success)
        val success = result as StrictDnsResult.Success
        assertEquals(listOf("10.0.0.1"), success.addresses)
        assertTrue(success.fromCache)
    }

    @Test
    fun `cache hit does not trigger StrictFailure even when all resolvers would fail`() {
        val transport = EncryptedResolverTransport { _, _, _, _ -> null }
        val cache = DnsCache { _, _ -> listOf("10.0.0.1") }
        val policy = FailoverPolicy(primary = primary)

        val result = StrictDnsResolver(transport, cache).resolve("proxy.example", "A", policy)

        assertTrue(result is StrictDnsResult.Success)
        assertFalse(result is StrictDnsResult.StrictFailure)
    }

    @Test
    fun `cache miss falls through to live resolver`() {
        val transport = EncryptedResolverTransport { _, _, _, _ -> listOf("5.6.7.8") }
        val cache = DnsCache { _, _ -> null }
        val policy = FailoverPolicy(primary = primary)

        val result = StrictDnsResolver(transport, cache).resolve("proxy.example", "A", policy)

        assertTrue(result is StrictDnsResult.Success)
        val success = result as StrictDnsResult.Success
        assertFalse(success.fromCache)
        assertEquals(listOf("5.6.7.8"), success.addresses)
    }

    @Test
    fun `cache miss with all resolvers failing returns StrictFailure`() {
        val transport = EncryptedResolverTransport { _, _, _, _ -> null }
        val cache = DnsCache { _, _ -> null }
        val policy = FailoverPolicy(primary = primary)

        val result = StrictDnsResolver(transport, cache).resolve("proxy.example", "A", policy)

        assertTrue(result is StrictDnsResult.StrictFailure)
    }

    @Test
    fun `cache is consulted per domain and qtype`() {
        val lookups = mutableListOf<Pair<String, String>>()
        val cache =
            DnsCache { domain, qtype ->
                lookups += domain to qtype
                null
            }
        val transport = EncryptedResolverTransport { _, _, _, _ -> listOf("1.2.3.4") }
        val policy = FailoverPolicy(primary = primary)

        StrictDnsResolver(transport, cache).resolve("proxy.example", "AAAA", policy)

        assertEquals(listOf("proxy.example" to "AAAA"), lookups)
    }

    @Test
    fun `NoOpDnsCache always returns null causing live resolution`() {
        var transportCalled = false
        val transport =
            EncryptedResolverTransport { _, _, _, _ ->
                transportCalled = true
                listOf("1.2.3.4")
            }
        val policy = FailoverPolicy(primary = primary)

        val result = StrictDnsResolver(transport, NoOpDnsCache).resolve("proxy.example", "A", policy)

        assertTrue(transportCalled)
        assertTrue(result is StrictDnsResult.Success)
        assertFalse((result as StrictDnsResult.Success).fromCache)
    }
}
