package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedDnsPathSelectionTest {
    @Test
    fun `automatic candidate plan excludes Cloudflare including remembered paths`() {
        val active = activeDnsSettings(dnsMode = DnsModeEncrypted, dnsProviderId = DnsProviderAdGuard, dnsIp = "")
        val excludedProviders = listOf(DnsProviderCloudflare, DnsProviderCloudflareIp, "cloudflare-malware")

        excludedProviders.forEach { providerId ->
            val remembered =
                activeDnsSettings(dnsMode = DnsModeEncrypted, dnsProviderId = providerId, dnsIp = "")
                    .toEncryptedDnsPathCandidate()
            val plan = buildEncryptedDnsCandidatePlan(active, preferredPath = remembered)

            assertTrue(plan.isNotEmpty())
            assertTrue(
                "Remembered $providerId must not become an automatic candidate",
                plan.none { it.resolverId in excludedProviders },
            )
        }
    }

    @Test
    fun `explicit Cloudflare selection remains usable with independent fallback candidates`() {
        val active =
            activeDnsSettings(
                dnsMode = DnsModeEncrypted,
                dnsProviderId = DnsProviderCloudflare,
                dnsIp = "",
            )

        val plan = buildEncryptedDnsCandidatePlan(active)

        assertEquals(active.toEncryptedDnsPathCandidate()?.pathKey(), plan.first().pathKey())
        assertEquals(1, plan.count { it.resolverId in setOf(DnsProviderCloudflare, DnsProviderCloudflareIp) })
        assertTrue(plan.any { it.resolverId == DnsProviderAdGuard })
        assertTrue(plan.any { it.resolverId == DnsProviderGoogle })
    }

    @Test
    fun `built in dot settings preserve provider identity and defaults`() {
        val active =
            activeDnsSettings(
                dnsMode = DnsModeEncrypted,
                dnsProviderId = DnsProviderCloudflare,
                dnsIp = "",
                encryptedDns = EncryptedDnsConfigInput(protocol = EncryptedDnsProtocolDot),
            )

        assertEquals(DnsProviderCloudflare, active.providerId)
        assertTrue(active.isDot)
        assertEquals("cloudflare-dns.com", active.encryptedDnsHost)
        assertEquals(853, active.encryptedDnsPort)
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), active.encryptedDnsBootstrapIps)
        assertEquals("Encrypted DNS · Cloudflare (DoT)", active.summary())
    }

    @Test
    fun `candidate plan interleaves protocols and starts with preferred path`() {
        val preferred =
            EncryptedDnsPathCandidate(
                resolverId = DnsProviderGoogle,
                resolverLabel = "Google Public DNS",
                protocol = EncryptedDnsProtocolDot,
                host = "dns.google",
                port = 853,
                tlsServerName = "dns.google",
                bootstrapIps = listOf("8.8.8.8", "8.8.4.4"),
            )

        val plan =
            buildEncryptedDnsCandidatePlan(
                activeDns =
                    activeDnsSettings(
                        dnsMode = DnsModePlainUdp,
                        dnsProviderId = DnsProviderCustom,
                        dnsIp = "1.1.1.1",
                    ),
                preferredPath = preferred,
            )

        assertEquals(preferred.pathKey(), plan.first().pathKey())
        assertTrue(plan.any { it.protocol == EncryptedDnsProtocolDoh })
        assertTrue(plan.any { it.protocol == EncryptedDnsProtocolDot })
        assertTrue(plan.zipWithNext().any { (left, right) -> left.protocol != right.protocol })
    }

    @Test
    fun `candidate plan carries current dnscrypt path ahead of built ins`() {
        val active =
            activeDnsSettings(
                dnsMode = DnsModeEncrypted,
                dnsProviderId = DnsProviderCustom,
                dnsIp = "",
                encryptedDns =
                    EncryptedDnsConfigInput(
                        protocol = EncryptedDnsProtocolDnsCrypt,
                        host = "dnscrypt.example.test",
                        port = 5443,
                        bootstrapIps = listOf("9.9.9.9"),
                        dnscryptProviderName = "2.dnscrypt-cert.example.test",
                        dnscryptPublicKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    ),
            )

        val plan = buildEncryptedDnsCandidatePlan(activeDns = active)

        assertEquals(EncryptedDnsProtocolDnsCrypt, plan.first().protocol)
        assertEquals("dnscrypt.example.test", plan.first().host)
        assertTrue(plan.any { it.protocol == EncryptedDnsProtocolDoh })
        assertTrue(plan.any { it.protocol == EncryptedDnsProtocolDot })
    }
}
