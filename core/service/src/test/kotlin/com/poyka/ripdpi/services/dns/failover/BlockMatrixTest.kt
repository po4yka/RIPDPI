package com.poyka.ripdpi.services.dns.failover

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that each blocked-transport scenario (DoH blocked, DoT blocked,
 * DoQ blocked, proxy-outbound failed) converges to StrictFailure.
 */
class BlockMatrixTest {
    private val domain = "proxy.example"
    private val qtype = "A"

    private fun failingTransport(): EncryptedResolverTransport = EncryptedResolverTransport { _, _, _, _ -> null }

    @Test
    fun `DoH blocked converges to StrictFailure`() {
        val transport =
            EncryptedResolverTransport { _, _, endpoint, _ ->
                if (endpoint.kind == EncryptedResolverKind.DohPost) null else listOf("1.2.3.4")
            }
        val policy =
            FailoverPolicy(
                primary = ResolverEndpoint(EncryptedResolverKind.DohPost, "https://doh.example.com/dns-query"),
            )
        val result = StrictDnsResolver(transport).resolve(domain, qtype, policy)

        assertTrue(result is StrictDnsResult.StrictFailure)
    }

    @Test
    fun `DoT blocked converges to StrictFailure`() {
        val transport =
            EncryptedResolverTransport { _, _, endpoint, _ ->
                if (endpoint.kind == EncryptedResolverKind.Dot) null else listOf("1.2.3.4")
            }
        val policy =
            FailoverPolicy(
                primary = ResolverEndpoint(EncryptedResolverKind.Dot, "dot.example.com"),
            )
        val result = StrictDnsResolver(transport).resolve(domain, qtype, policy)

        assertTrue(result is StrictDnsResult.StrictFailure)
    }

    @Test
    fun `DoQ blocked converges to StrictFailure`() {
        val transport =
            EncryptedResolverTransport { _, _, endpoint, _ ->
                if (endpoint.kind == EncryptedResolverKind.Doq) null else listOf("1.2.3.4")
            }
        val policy =
            FailoverPolicy(
                primary = ResolverEndpoint(EncryptedResolverKind.Doq, "doq.example.com"),
            )
        val result = StrictDnsResolver(transport).resolve(domain, qtype, policy)

        assertTrue(result is StrictDnsResult.StrictFailure)
    }

    @Test
    fun `proxy outbound failure converges to StrictFailure when no fallback outbounds configured`() {
        val result =
            StrictDnsResolver(failingTransport()).resolve(
                domain,
                qtype,
                FailoverPolicy(
                    primary = ResolverEndpoint(EncryptedResolverKind.DohPost, "https://doh.example.com/dns-query"),
                ),
            )

        assertTrue(result is StrictDnsResult.StrictFailure)
    }

    @Test
    fun `all protocols blocked across all outbounds converges to StrictFailure`() {
        val result =
            StrictDnsResolver(failingTransport()).resolve(
                domain,
                qtype,
                FailoverPolicy(
                    primary = ResolverEndpoint(EncryptedResolverKind.DohPost, "https://doh.example.com/dns-query"),
                    secondaries =
                        listOf(
                            ResolverEndpoint(EncryptedResolverKind.Dot, "dot.example.com"),
                            ResolverEndpoint(EncryptedResolverKind.Doq, "doq.example.com"),
                        ),
                    fallbackOutbounds = listOf("outbound-a", "outbound-b"),
                ),
            )

        assertTrue(result is StrictDnsResult.StrictFailure)
    }

    @Test
    fun `mixed protocol block with one working fallback outbound succeeds`() {
        val transport =
            EncryptedResolverTransport { _, _, endpoint, outbound ->
                if (outbound == "outbound-ok" && endpoint.kind == EncryptedResolverKind.Dot) {
                    listOf("9.9.9.9")
                } else {
                    null
                }
            }
        val policy =
            FailoverPolicy(
                primary = ResolverEndpoint(EncryptedResolverKind.DohPost, "https://doh.example.com/dns-query"),
                secondaries = listOf(ResolverEndpoint(EncryptedResolverKind.Dot, "dot.example.com")),
                fallbackOutbounds = listOf("outbound-ok"),
            )
        val result = StrictDnsResolver(transport).resolve(domain, qtype, policy)

        assertTrue(result is StrictDnsResult.Success)
    }
}
