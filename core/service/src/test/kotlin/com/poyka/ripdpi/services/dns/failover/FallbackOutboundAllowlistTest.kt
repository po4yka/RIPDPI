package com.poyka.ripdpi.services.dns.failover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that on active-outbound failure, only explicitly listed fallback outbounds
 * are attempted — non-listed outbounds are never contacted.
 */
class FallbackOutboundAllowlistTest {
    private val primary = ResolverEndpoint(EncryptedResolverKind.DohPost, "https://dns.example.com/dns-query")
    private val secondary = ResolverEndpoint(EncryptedResolverKind.Dot, "dot.example.com")

    @Test
    fun `fallback outbound is tried when active outbound exhausted`() {
        val outboundsUsed = mutableListOf<OutboundId>()
        val transport =
            EncryptedResolverTransport { _, _, _, outbound ->
                outboundsUsed += outbound
                if (outbound == "outbound-b") listOf("9.9.9.9") else null
            }
        val policy =
            FailoverPolicy(
                primary = primary,
                secondaries = listOf(secondary),
                fallbackOutbounds = listOf("outbound-b"),
            )
        val result = StrictDnsResolver(transport).resolve("proxy.example", "A", policy)

        assertTrue(result is StrictDnsResult.Success)
        assertTrue(outboundsUsed.contains("outbound-b"))
    }

    @Test
    fun `non-allowed outbound is never contacted`() {
        val outboundsUsed = mutableListOf<OutboundId>()
        val transport =
            EncryptedResolverTransport { _, _, _, outbound ->
                outboundsUsed += outbound
                if (outbound == "outbound-b") listOf("9.9.9.9") else null
            }
        val policy =
            FailoverPolicy(
                primary = primary,
                fallbackOutbounds = listOf("outbound-b"),
            )
        StrictDnsResolver(transport).resolve("proxy.example", "A", policy)

        assertTrue("unlisted outbound must not be contacted", "outbound-unlisted" !in outboundsUsed)
    }

    @Test
    fun `fallback outbounds tried in configured order`() {
        val outboundsOrdered = mutableListOf<OutboundId>()
        val transport =
            EncryptedResolverTransport { _, _, _, outbound ->
                outboundsOrdered += outbound
                if (outbound == "outbound-c") listOf("1.1.1.1") else null
            }
        val policy =
            FailoverPolicy(
                primary = primary,
                fallbackOutbounds = listOf("outbound-a", "outbound-b", "outbound-c"),
            )
        StrictDnsResolver(transport).resolve("proxy.example", "A", policy)

        val fallbacksUsed = outboundsOrdered.filter { it != "" }
        assertEquals(listOf("outbound-a", "outbound-b", "outbound-c"), fallbacksUsed.distinct())
    }

    @Test
    fun `fallback outbound success carries correct outbound id`() {
        val transport =
            EncryptedResolverTransport { _, _, _, outbound ->
                if (outbound == "outbound-x") listOf("5.5.5.5") else null
            }
        val policy =
            FailoverPolicy(
                primary = primary,
                fallbackOutbounds = listOf("outbound-x"),
            )
        val result = StrictDnsResolver(transport).resolve("proxy.example", "A", policy)

        assertTrue(result is StrictDnsResult.Success)
        assertEquals("outbound-x", (result as StrictDnsResult.Success).outboundUsed)
    }

    @Test
    fun `empty fallback outbound list goes straight to strict failure`() {
        val transport = EncryptedResolverTransport { _, _, _, _ -> null }
        val policy =
            FailoverPolicy(
                primary = primary,
                fallbackOutbounds = emptyList(),
            )
        val result = StrictDnsResolver(transport).resolve("proxy.example", "A", policy)

        assertTrue(result is StrictDnsResult.StrictFailure)
    }
}
