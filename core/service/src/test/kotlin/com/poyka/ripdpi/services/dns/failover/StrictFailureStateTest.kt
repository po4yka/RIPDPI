package com.poyka.ripdpi.services.dns.failover

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that when all resolver paths are exhausted, StrictDnsResult.StrictFailure
 * is returned. Callers must treat this as SERVFAIL / blocked.
 */
class StrictFailureStateTest {
    private val primary = ResolverEndpoint(EncryptedResolverKind.DohPost, "https://dns.example.com/dns-query")
    private val secondary = ResolverEndpoint(EncryptedResolverKind.Dot, "dot.example.com")

    @Test
    fun `all paths exhausted returns StrictFailure`() {
        val transport = EncryptedResolverTransport { _, _, _, _ -> null }
        val policy =
            FailoverPolicy(
                primary = primary,
                secondaries = listOf(secondary),
                fallbackOutbounds = listOf("outbound-a", "outbound-b"),
            )
        val result = StrictDnsResolver(transport).resolve("proxy.example", "A", policy)

        assertTrue(result is StrictDnsResult.StrictFailure)
    }

    @Test
    fun `no secondaries and no fallbacks returns StrictFailure`() {
        val transport = EncryptedResolverTransport { _, _, _, _ -> null }
        val policy = FailoverPolicy(primary = primary)
        val result = StrictDnsResolver(transport).resolve("proxy.example", "A", policy)

        assertTrue(result is StrictDnsResult.StrictFailure)
    }

    @Test
    fun `StrictFailure is returned not a plaintext address`() {
        val transport = EncryptedResolverTransport { _, _, _, _ -> null }
        val policy =
            FailoverPolicy(
                primary = primary,
                fallbackOutbounds = listOf("outbound-a"),
            )
        val result = StrictDnsResolver(transport).resolve("proxy.example", "A", policy)

        assertTrue(
            "Expected StrictFailure but got $result",
            result is StrictDnsResult.StrictFailure,
        )
    }

    @Test
    fun `partial secondary failure still falls through to StrictFailure when all exhausted`() {
        val dotEndpoint = ResolverEndpoint(EncryptedResolverKind.Dot, "dot.example.com")
        val doqEndpoint = ResolverEndpoint(EncryptedResolverKind.Doq, "doq.example.com")
        val transport = EncryptedResolverTransport { _, _, _, _ -> null }
        val policy =
            FailoverPolicy(
                primary = primary,
                secondaries = listOf(dotEndpoint, doqEndpoint),
                fallbackOutbounds = listOf("outbound-a"),
            )
        val result = StrictDnsResolver(transport).resolve("proxy.example", "A", policy)

        assertTrue(result is StrictDnsResult.StrictFailure)
    }
}
