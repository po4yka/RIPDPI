package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CloudflareDnsRemovedFromCriticalListTest {
    private val cloudflareProviderIds = setOf(DnsProviderCloudflare, DnsProviderCloudflareIp)
    private val cloudflareEndpoints = setOf("1.1.1.1", "1.0.0.1", "cloudflare-dns.com")

    @Test
    fun `filterForCriticalPath removes Cloudflare resolvers from a mixed list`() {
        val mixed =
            listOf(
                CriticalResolverEntry(
                    resolverId = DnsProviderCloudflare,
                    host = "cloudflare-dns.com",
                    tlsServerName = "cloudflare-dns.com",
                    bootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
                    dohUrl = "https://cloudflare-dns.com/dns-query",
                ),
                CriticalResolverEntry(
                    resolverId = DnsProviderQuad9,
                    host = "dns.quad9.net",
                    tlsServerName = "dns.quad9.net",
                    bootstrapIps = listOf("9.9.9.9"),
                    dohUrl = "https://dns.quad9.net/dns-query",
                ),
                CriticalResolverEntry(
                    resolverId = DnsProviderCloudflareIp,
                    host = "1.1.1.1",
                    tlsServerName = "cloudflare-dns.com",
                    bootstrapIps = listOf("1.1.1.1"),
                    dohUrl = "https://1.1.1.1/dns-query",
                ),
            )

        val filtered = CriticalResolverChainBuilder.filterForCriticalPath(mixed, allowCloudflare = false)

        assertFalse(
            "Filtered list must not contain Cloudflare provider IDs",
            filtered.any { it.resolverId in cloudflareProviderIds },
        )
        assertFalse(
            "Filtered list must not contain Cloudflare bootstrap IPs",
            filtered.any { entry -> entry.bootstrapIps.any { ip -> ip in cloudflareEndpoints } },
        )
        assertEquals(1, filtered.size)
        assertEquals(DnsProviderQuad9, filtered.first().resolverId)
    }

    @Test
    fun `filterForCriticalPath with allowCloudflare true preserves Cloudflare resolvers`() {
        val mixed =
            listOf(
                CriticalResolverEntry(
                    resolverId = DnsProviderCloudflare,
                    host = "cloudflare-dns.com",
                    tlsServerName = "cloudflare-dns.com",
                    bootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
                    dohUrl = "https://cloudflare-dns.com/dns-query",
                ),
                CriticalResolverEntry(
                    resolverId = DnsProviderQuad9,
                    host = "dns.quad9.net",
                    tlsServerName = "dns.quad9.net",
                    bootstrapIps = listOf("9.9.9.9"),
                    dohUrl = "https://dns.quad9.net/dns-query",
                ),
            )

        val filtered = CriticalResolverChainBuilder.filterForCriticalPath(mixed, allowCloudflare = true)

        assertEquals(2, filtered.size)
    }

    @Test
    fun `default BuiltInDnsProviders first entry is not a Cloudflare provider`() {
        val first = BuiltInDnsProviders.firstOrNull()
        assertFalse(
            "First BuiltInDnsProvider must not be a Cloudflare provider (was ${first?.providerId})",
            first?.providerId in cloudflareProviderIds,
        )
    }
}
