package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsResolverConfigTest {
    @Test
    fun `canonical defaults use first built in resolver`() {
        val defaultProvider = canonicalDefaultDnsProviderDefinition()
        val defaultSettings = canonicalDefaultEncryptedDnsSettings()
        val defaultPath = canonicalDefaultEncryptedDnsPathCandidate()

        assertEquals(BuiltInDnsProviders.first(), defaultProvider)
        assertEquals("94.140.14.14", canonicalDefaultPlainDnsIp())
        assertEquals("94.140.14.14:53", canonicalDefaultUdpDnsServer())
        assertEquals(defaultProvider.providerId, defaultSettings.providerId)
        assertEquals(defaultSettings.providerId, defaultPath.resolverId)
        assertEquals(defaultSettings.encryptedDnsHost, defaultPath.host)
    }

    @Test
    fun `serializer default dns matches canonical encrypted settings`() {
        val defaultSettings = canonicalDefaultEncryptedDnsSettings()
        val persistedDefaults = AppSettingsSerializer.defaultValue.activeDnsSettings()

        assertEquals(defaultSettings, persistedDefaults)
    }

    @Test
    fun `built in encrypted dns summary uses provider display name`() {
        val active =
            activeDnsSettings(
                dnsMode = DnsModeEncrypted,
                dnsProviderId = DnsProviderCloudflare,
                dnsIp = "",
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
            )

        assertTrue(active.isPlainUdp)
        assertEquals("Plain DNS · 9.9.9.9", active.summary())
    }

    // -- URL parsing error paths -----------------------------------------------

    @Test
    fun `custom doh with malformed url falls back to safe defaults`() {
        val active =
            activeDnsSettings(
                dnsMode = DnsModeEncrypted,
                dnsProviderId = DnsProviderCustom,
                dnsIp = "",
                encryptedDnsProtocol = EncryptedDnsProtocolDoh,
                encryptedDnsDohUrl = "not a url at all ::::",
                encryptedDnsBootstrapIps = listOf("1.2.3.4"),
            )

        assertEquals("1.2.3.4", active.dnsIp)
        assertEquals(443, active.encryptedDnsPort)
    }

    @Test
    fun `custom doh with empty url derives port 443 by default`() {
        val active =
            activeDnsSettings(
                dnsMode = DnsModeEncrypted,
                dnsProviderId = DnsProviderCustom,
                dnsIp = "",
                encryptedDnsProtocol = EncryptedDnsProtocolDoh,
                encryptedDnsHost = "doh.example.test",
                encryptedDnsBootstrapIps = listOf("10.0.0.1"),
            )

        assertEquals(443, active.encryptedDnsPort)
        assertEquals("doh.example.test", active.encryptedDnsHost)
    }

    @Test
    fun `custom doh with valid url derives host and port from url`() {
        val active =
            activeDnsSettings(
                dnsMode = DnsModeEncrypted,
                dnsProviderId = DnsProviderCustom,
                dnsIp = "",
                encryptedDnsProtocol = EncryptedDnsProtocolDoh,
                encryptedDnsDohUrl = "https://dns.example.test:8443/dns-query",
                encryptedDnsBootstrapIps = listOf("10.0.0.2"),
            )

        assertEquals("dns.example.test", active.encryptedDnsHost)
        assertEquals(8443, active.encryptedDnsPort)
    }

    @Test
    fun `custom doh with http scheme url derives port 80`() {
        val active =
            activeDnsSettings(
                dnsMode = DnsModeEncrypted,
                dnsProviderId = DnsProviderCustom,
                dnsIp = "",
                encryptedDnsProtocol = EncryptedDnsProtocolDoh,
                encryptedDnsDohUrl = "http://dns.example.test/dns-query",
                encryptedDnsBootstrapIps = listOf("10.0.0.3"),
            )

        assertEquals(80, active.encryptedDnsPort)
    }

    @Test
    fun `normalize dns bootstrap ips deduplicates and trims whitespace`() {
        val normalized = normalizeDnsBootstrapIps(listOf("  8.8.8.8 ", "1.1.1.1,8.8.8.8", "  "))

        assertEquals(listOf("8.8.8.8", "1.1.1.1"), normalized)
    }
}
