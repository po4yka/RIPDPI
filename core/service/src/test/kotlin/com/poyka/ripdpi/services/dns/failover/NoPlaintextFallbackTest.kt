package com.poyka.ripdpi.services.dns.failover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security invariant: no plaintext system-DNS query is ever issued for proxy-class
 * domains, even on total encrypted-resolver failure.
 *
 * The type system prevents adding a plaintext variant to [EncryptedResolverKind],
 * [FailoverPolicy], or [StrictDnsResult]. These tests verify the behavioural and
 * structural guarantees that enforce this invariant.
 */
class NoPlaintextFallbackTest {
    private val primary = ResolverEndpoint(EncryptedResolverKind.DohPost, "https://dns.example.com/dns-query")

    @Test
    fun `on total failure result is StrictFailure not a system DNS address`() {
        val transport = EncryptedResolverTransport { _, _, _, _ -> null }
        val policy =
            FailoverPolicy(
                primary = primary,
                fallbackOutbounds = listOf("outbound-a"),
            )
        val result = StrictDnsResolver(transport).resolve("proxy.example", "A", policy)

        assertTrue(result is StrictDnsResult.StrictFailure)
    }

    @Test
    fun `transport is never called with a plaintext resolver endpoint kind`() {
        val kindsUsed = mutableListOf<EncryptedResolverKind>()
        val transport =
            EncryptedResolverTransport { _, _, endpoint, _ ->
                kindsUsed += endpoint.kind
                null
            }
        val policy =
            FailoverPolicy(
                primary = primary,
                secondaries =
                    listOf(
                        ResolverEndpoint(EncryptedResolverKind.Dot, "dot.example.com"),
                        ResolverEndpoint(EncryptedResolverKind.Doq, "doq.example.com"),
                    ),
                fallbackOutbounds = listOf("outbound-b"),
            )
        StrictDnsResolver(transport).resolve("proxy.example", "A", policy)

        val allowedKinds =
            setOf(
                EncryptedResolverKind.DohPost,
                EncryptedResolverKind.Dot,
                EncryptedResolverKind.Doq,
            )
        assertTrue(
            "All transport calls must use encrypted resolver kinds",
            kindsUsed.all { it in allowedKinds },
        )
    }

    @Test
    fun `EncryptedResolverKind has no plaintext variant`() {
        val kinds = EncryptedResolverKind.entries.map { it.name }
        assertFalse("NONE must not exist in EncryptedResolverKind", "NONE" in kinds)
        assertFalse("PLAINTEXT must not exist in EncryptedResolverKind", "PLAINTEXT" in kinds)
        assertFalse("SYSTEM must not exist in EncryptedResolverKind", "SYSTEM" in kinds)
    }

    @Test
    fun `StrictDnsResult sealed class has no plaintext fallback variant`() {
        // Exhaustive when — compiler rejects any new variant that isn't handled here.
        // If a plaintext/system variant were ever added, this function would fail to compile.
        fun isKnownSafeVariant(r: StrictDnsResult): Boolean =
            when (r) {
                is StrictDnsResult.Success -> true
                StrictDnsResult.StrictFailure -> true
            }
        val transport = EncryptedResolverTransport { _, _, _, _ -> null }
        val result = StrictDnsResolver(transport).resolve("proxy.example", "A", FailoverPolicy(primary = primary))
        assertTrue(isKnownSafeVariant(result))
    }

    @Test
    fun `transport receives null result and resolver returns StrictFailure not fallback address`() {
        var transportCalled = false
        val transport =
            EncryptedResolverTransport { _, _, _, _ ->
                transportCalled = true
                null
            }
        val policy = FailoverPolicy(primary = primary)
        val result = StrictDnsResolver(transport).resolve("blocked.proxy.example", "A", policy)

        assertTrue(transportCalled)
        assertTrue(result is StrictDnsResult.StrictFailure)
    }
}
