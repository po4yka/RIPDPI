package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsResolverConfigTest {
    @Test
    fun `built in encrypted dns summary uses provider display name`() {
        val active =
            activeDnsSettings(
                dnsMode = DnsModeEncrypted,
                dnsProviderId = DnsProviderCloudflare,
                dnsIp = "",
                dnsDohUrl = "",
                dnsDohBootstrapIps = emptyList(),
                encryptedDnsProtocol = EncryptedDnsProtocolDoh,
            )

        assertTrue(active.isEncrypted)
        assertTrue(active.isDoh)
        assertEquals("Cloudflare", active.providerDisplayName)
        assertEquals("Encrypted DNS · Cloudflare (DoH)", active.summary())
    }

    @Test
    fun `custom dot resolver summary uses custom label and normalized defaults`() {
        val active =
            activeDnsSettings(
                dnsMode = DnsModeEncrypted,
                dnsProviderId = DnsProviderCustom,
                dnsIp = "",
                dnsDohUrl = "",
                dnsDohBootstrapIps = emptyList(),
                encryptedDnsProtocol = EncryptedDnsProtocolDot,
                encryptedDnsHost = "dot.example.test",
                encryptedDnsTlsServerName = "dot.example.test",
                encryptedDnsBootstrapIps = listOf("9.9.9.9", " 149.112.112.112 ", "9.9.9.9"),
            )

        assertTrue(active.isDot)
        assertEquals("9.9.9.9", active.dnsIp)
        assertEquals(853, active.encryptedDnsPort)
        assertEquals(listOf("9.9.9.9", "149.112.112.112"), active.encryptedDnsBootstrapIps)
        assertEquals("Custom resolver", active.providerDisplayName)
        assertEquals("Encrypted DNS · Custom resolver (DoT)", active.summary())
    }

    @Test
    fun `custom dnscrypt resolver summary preserves provider metadata`() {
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
                encryptedDnsBootstrapIps = listOf("8.8.8.8", "8.8.4.4"),
                encryptedDnsDnscryptProviderName = "2.dnscrypt-cert.example.test",
                encryptedDnsDnscryptPublicKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            )

        assertTrue(active.isDnsCrypt)
        assertEquals("8.8.8.8", active.dnsIp)
        assertEquals("2.dnscrypt-cert.example.test", active.encryptedDnsDnscryptProviderName)
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            active.encryptedDnsDnscryptPublicKey,
        )
        assertEquals("Encrypted DNS · Custom resolver (DNSCrypt)", active.summary())
    }

    @Test
    fun `plain dns summary uses configured ip`() {
        val active =
            activeDnsSettings(
                dnsMode = DnsModePlainUdp,
                dnsProviderId = DnsProviderCustom,
                dnsIp = "9.9.9.9",
                dnsDohUrl = "",
                dnsDohBootstrapIps = emptyList(),
            )

        assertTrue(active.isPlainUdp)
        assertEquals("Plain DNS · 9.9.9.9", active.summary())
    }
}
