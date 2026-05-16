package com.poyka.ripdpi.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareDnsExplicitOnlyTest {
    @Test
    fun `default profile does not allow Cloudflare DNS in critical chain`() {
        val chain = CriticalResolverChainBuilder.build(CriticalResolverProfile.Default)
        val cloudflareProviderIds = setOf(DnsProviderCloudflare, DnsProviderCloudflareIp)
        val hasCloudflare = chain.any { it.resolverId in cloudflareProviderIds }
        assertFalse(
            "Default critical chain must not contain Cloudflare provider IDs, got: ${chain.map { it.resolverId }}",
            hasCloudflare,
        )
    }

    @Test
    fun `explicit-opt-in profile permits Cloudflare DNS in critical chain`() {
        val chain = CriticalResolverChainBuilder.build(CriticalResolverProfile.CloudflareAllowed)
        val cloudflareProviderIds = setOf(DnsProviderCloudflare, DnsProviderCloudflareIp)
        val hasCloudflare = chain.any { it.resolverId in cloudflareProviderIds }
        assertTrue(
            "CloudflareAllowed critical chain must contain at least one Cloudflare provider",
            hasCloudflare,
        )
    }

    @Test
    fun `default profile critical chain contains at least one non-Cloudflare resolver`() {
        val chain = CriticalResolverChainBuilder.build(CriticalResolverProfile.Default)
        val cloudflareProviderIds = setOf(DnsProviderCloudflare, DnsProviderCloudflareIp)
        val nonCloudflare = chain.filter { it.resolverId !in cloudflareProviderIds }
        assertTrue(
            "Default critical chain must have at least one non-Cloudflare resolver",
            nonCloudflare.isNotEmpty(),
        )
    }
}
