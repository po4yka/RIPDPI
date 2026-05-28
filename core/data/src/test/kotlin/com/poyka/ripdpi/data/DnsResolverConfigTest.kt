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
                encryptedDns = EncryptedDnsConfigInput(protocol = EncryptedDnsProtocolDoh),
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
                encryptedDns =
                    EncryptedDnsConfigInput(
                        protocol = EncryptedDnsProtocolDot,
                        host = "dot.example.test",
                        tlsServerName = "dot.example.test",
                        bootstrapIps = listOf("9.9.9.9", " 149.112.112.112 ", "9.9.9.9"),
                    ),
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
                encryptedDns =
                    EncryptedDnsConfigInput(
                        protocol = EncryptedDnsProtocolDnsCrypt,
                        host = "dnscrypt.example.test",
                        port = 5443,
                        bootstrapIps = listOf("8.8.8.8", "8.8.4.4"),
                        dnscryptProviderName = "2.dnscrypt-cert.example.test",
                        dnscryptPublicKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    ),
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
    fun `custom odoh resolver preserves proxy target and config source`() {
        val active =
            activeDnsSettings(
                dnsMode = DnsModeEncrypted,
                dnsProviderId = DnsProviderCustom,
                dnsIp = "",
                encryptedDns =
                    EncryptedDnsConfigInput(
                        protocol = EncryptedDnsProtocolOdoh,
                        bootstrapIps = listOf("203.0.113.10", " 203.0.113.10 "),
                        odohProxyUrl = "https://proxy.example.test:9443/proxy",
                        odohProxyOperatorId = "ProxyNet",
                        odohTargetHost = "target.example.test",
                        odohTargetPath = "/dns-query",
                        odohTargetOperatorId = "TargetNet",
                        odohConfigSource = EncryptedDnsOdohConfigSourceCustomBytes,
                        odohConfigsHex = "00aa",
                        odohConfigsRetrievedAtSecs = 1_700_000_000L,
                        odohConfigsTtlSecs = 86_400L,
                    ),
            )

        assertTrue(active.isOdoh)
        assertEquals(EncryptedDnsProtocolOdoh, active.encryptedDnsProtocol)
        assertEquals("proxy.example.test", active.encryptedDnsHost)
        assertEquals(9443, active.encryptedDnsPort)
        assertEquals("proxy.example.test", active.encryptedDnsTlsServerName)
        assertEquals(listOf("203.0.113.10"), active.encryptedDnsBootstrapIps)
        assertEquals("https://proxy.example.test:9443/proxy", active.encryptedDnsOdohProxyUrl)
        assertEquals("ProxyNet", active.encryptedDnsOdohProxyOperatorId)
        assertEquals("target.example.test", active.encryptedDnsOdohTargetHost)
        assertEquals("/dns-query", active.encryptedDnsOdohTargetPath)
        assertEquals("TargetNet", active.encryptedDnsOdohTargetOperatorId)
        assertEquals(EncryptedDnsOdohConfigSourceCustomBytes, active.encryptedDnsOdohConfigSource)
        assertEquals("00aa", active.encryptedDnsOdohConfigsHex)
        assertEquals(1_700_000_000L, active.encryptedDnsOdohConfigsRetrievedAtSecs)
        assertEquals(86_400L, active.encryptedDnsOdohConfigsTtlSecs)
        assertEquals("Encrypted DNS · Custom resolver (ODoH)", active.summary())
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
                encryptedDns =
                    EncryptedDnsConfigInput(
                        protocol = EncryptedDnsProtocolDoh,
                        dohUrl = "not a url at all ::::",
                        bootstrapIps = listOf("1.2.3.4"),
                    ),
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
                encryptedDns =
                    EncryptedDnsConfigInput(
                        protocol = EncryptedDnsProtocolDoh,
                        host = "doh.example.test",
                        bootstrapIps = listOf("10.0.0.1"),
                    ),
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
                encryptedDns =
                    EncryptedDnsConfigInput(
                        protocol = EncryptedDnsProtocolDoh,
                        dohUrl = "https://dns.example.test:8443/dns-query",
                        bootstrapIps = listOf("10.0.0.2"),
                    ),
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
                encryptedDns =
                    EncryptedDnsConfigInput(
                        protocol = EncryptedDnsProtocolDoh,
                        dohUrl = "http://dns.example.test/dns-query",
                        bootstrapIps = listOf("10.0.0.3"),
                    ),
            )

        assertEquals(80, active.encryptedDnsPort)
    }

    @Test
    fun `normalize dns bootstrap ips deduplicates and trims whitespace`() {
        val normalized = normalizeDnsBootstrapIps(listOf("  8.8.8.8 ", "1.1.1.1,8.8.8.8", "  "))

        assertEquals(listOf("8.8.8.8", "1.1.1.1"), normalized)
    }
}
