package com.poyka.ripdpi.services.dns.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the primary encrypted resolver is tried first via the active outbound,
 * and that secondaries are attempted in order only after the primary fails.
 */
class EncryptedResolverOrderTest {
    private val dohPrimary = ResolverEndpoint(EncryptedResolverKind.DohPost, "https://dns.example.com/dns-query")
    private val dotSecondary = ResolverEndpoint(EncryptedResolverKind.Dot, "dns.fallback.example")
    private val doqSecondary = ResolverEndpoint(EncryptedResolverKind.Doq, "dns.quic.example")

    @Test
    fun `primary resolver is tried first and returns success`() {
        val calls = mutableListOf<Pair<ResolverEndpoint, OutboundId>>()
        val transport =
            EncryptedResolverTransport { _, _, endpoint, outbound ->
                calls += endpoint to outbound
                listOf("1.2.3.4")
            }
        val policy =
            FailoverPolicy(
                primary = dohPrimary,
                secondaries = listOf(dotSecondary),
            )
        val result = StrictDnsResolver(transport).resolve("example.com", "A", policy)

        assertTrue(result is StrictDnsResult.Success)
        assertEquals(1, calls.size)
        assertEquals(dohPrimary, calls[0].first)
        assertEquals("", calls[0].second)
    }

    @Test
    fun `secondary resolver tried after primary fails`() {
        val calls = mutableListOf<ResolverEndpoint>()
        val transport =
            EncryptedResolverTransport { _, _, endpoint, _ ->
                calls += endpoint
                if (endpoint == dotSecondary) listOf("5.6.7.8") else null
            }
        val policy =
            FailoverPolicy(
                primary = dohPrimary,
                secondaries = listOf(dotSecondary),
            )
        val result = StrictDnsResolver(transport).resolve("example.com", "A", policy)

        assertTrue(result is StrictDnsResult.Success)
        assertEquals(listOf(dohPrimary, dotSecondary), calls)
        assertEquals("5.6.7.8", (result as StrictDnsResult.Success).addresses.first())
    }

    @Test
    fun `secondaries tried in configured order`() {
        val calls = mutableListOf<ResolverEndpoint>()
        val transport =
            EncryptedResolverTransport { _, _, endpoint, _ ->
                calls += endpoint
                if (endpoint == doqSecondary) listOf("9.10.11.12") else null
            }
        val policy =
            FailoverPolicy(
                primary = dohPrimary,
                secondaries = listOf(dotSecondary, doqSecondary),
            )
        StrictDnsResolver(transport).resolve("proxy.example", "A", policy)

        assertEquals(listOf(dohPrimary, dotSecondary, doqSecondary), calls)
    }

    @Test
    fun `all resolvers via active outbound use empty outbound id`() {
        val outboundsUsed = mutableListOf<OutboundId>()
        val transport =
            EncryptedResolverTransport { _, _, endpoint, outbound ->
                outboundsUsed += outbound
                if (endpoint == doqSecondary) listOf("1.1.1.1") else null
            }
        val policy =
            FailoverPolicy(
                primary = dohPrimary,
                secondaries = listOf(dotSecondary, doqSecondary),
            )
        StrictDnsResolver(transport).resolve("proxy.example", "A", policy)

        assertTrue(outboundsUsed.all { it == "" })
    }

    @Test
    fun `result carries correct outbound id on success`() {
        val transport = EncryptedResolverTransport { _, _, _, _ -> listOf("1.2.3.4") }
        val policy = FailoverPolicy(primary = dohPrimary)
        val result = StrictDnsResolver(transport).resolve("a.example", "A", policy) as StrictDnsResult.Success

        assertEquals("", result.outboundUsed)
        assertFalse(result.fromCache)
    }
}
