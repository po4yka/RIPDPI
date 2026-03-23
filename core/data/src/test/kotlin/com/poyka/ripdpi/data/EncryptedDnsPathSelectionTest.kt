package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedDnsPathSelectionTest {
    @Test
    fun `built in dot settings preserve provider identity and defaults`() {
        val active =
            activeDnsSettings(
                dnsMode = DnsModeEncrypted,
                dnsProviderId = DnsProviderCloudflare,
                dnsIp = "",
                dnsDohUrl = "",
                dnsDohBootstrapIps = emptyList(),
                encryptedDnsProtocol = EncryptedDnsProtocolDot,
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
                        dnsDohUrl = "",
                        dnsDohBootstrapIps = emptyList(),
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
                dnsDohUrl = "",
                dnsDohBootstrapIps = emptyList(),
                encryptedDnsProtocol = EncryptedDnsProtocolDnsCrypt,
                encryptedDnsHost = "dnscrypt.example.test",
                encryptedDnsPort = 5443,
                encryptedDnsBootstrapIps = listOf("9.9.9.9"),
                encryptedDnsDnscryptProviderName = "2.dnscrypt-cert.example.test",
                encryptedDnsDnscryptPublicKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            )

        val plan = buildEncryptedDnsCandidatePlan(activeDns = active)

        assertEquals(EncryptedDnsProtocolDnsCrypt, plan.first().protocol)
        assertEquals("dnscrypt.example.test", plan.first().host)
        assertTrue(plan.any { it.protocol == EncryptedDnsProtocolDoh })
        assertTrue(plan.any { it.protocol == EncryptedDnsProtocolDot })
    }
}
