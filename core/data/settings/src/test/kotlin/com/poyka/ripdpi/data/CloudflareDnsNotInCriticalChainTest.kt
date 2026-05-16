package com.poyka.ripdpi.data

import org.junit.Assert.assertFalse
import org.junit.Test

private val cloudflareEndpoints =
    setOf(
        "1.1.1.1",
        "1.0.0.1",
        "cloudflare-dns.com",
    )

private fun String.containsCloudflareEndpoint(): Boolean =
    cloudflareEndpoints.any { endpoint -> this.contains(endpoint, ignoreCase = true) }

private fun List<String>.containsCloudflareEndpoint(): Boolean = any { it.containsCloudflareEndpoint() }

class CloudflareDnsNotInCriticalChainTest {
    private val chain = CriticalResolverChainBuilder.build(CriticalResolverProfile.Default)

    @Test
    fun `default critical chain bootstrap IPs do not contain Cloudflare addresses`() {
        val bootstrapIps = chain.flatMap { it.bootstrapIps }
        assertFalse(
            "Bootstrap IPs must not contain Cloudflare DNS: $bootstrapIps",
            bootstrapIps.containsCloudflareEndpoint(),
        )
    }

    @Test
    fun `default critical chain primary hosts do not reference Cloudflare`() {
        val hosts = chain.map { it.host }
        assertFalse(
            "Primary hosts must not reference Cloudflare DNS: $hosts",
            hosts.containsCloudflareEndpoint(),
        )
    }

    @Test
    fun `default critical chain DoH URLs do not reference Cloudflare`() {
        val urls = chain.mapNotNull { it.dohUrl }
        assertFalse(
            "DoH URLs must not reference Cloudflare DNS: $urls",
            urls.containsCloudflareEndpoint(),
        )
    }

    @Test
    fun `default critical chain TLS server names do not reference Cloudflare`() {
        val tlsNames = chain.map { it.tlsServerName }
        assertFalse(
            "TLS server names must not reference Cloudflare DNS: $tlsNames",
            tlsNames.containsCloudflareEndpoint(),
        )
    }
}
