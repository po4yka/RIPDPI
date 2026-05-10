package com.poyka.ripdpi.data.diagnostics

import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderCustom
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class DetectionResolverNetworkStackTest {
    @Test
    fun `client is cached when config is unchanged`() {
        val stack = DetectionResolverNetworkStack()
        val config = DetectionResolverConfig()

        assertSame(stack.clientFor(config), stack.clientFor(config))
    }

    @Test
    fun `client is rebuilt when resolver mode changes`() {
        val stack = DetectionResolverNetworkStack()
        val systemConfig = DetectionResolverConfig()
        val directConfig =
            DetectionResolverConfig(
                resolverMode = DetectionResolverMode.DIRECT,
                directDnsServers = listOf("8.8.8.8"),
            )

        assertNotSame(stack.clientFor(systemConfig), stack.clientFor(directConfig))
    }

    @Test
    fun `dns presets include required providers`() {
        assertSame(DnsPreset.CLOUDFLARE, DnsPreset.byName("cloudflare"))
        assertSame(DnsPreset.GOOGLE, DnsPreset.byName("google"))
        assertSame(DnsPreset.YANDEX, DnsPreset.byName("yandex"))
    }

    @Test
    fun `plain active dns settings map to direct mode`() {
        val config =
            DetectionResolverConfig.fromActiveDnsSettings(
                activeDnsSettings(
                    mode = DnsModePlainUdp,
                    providerId = DnsProviderCustom,
                    dnsIp = "77.88.8.8",
                ),
            )

        assertEquals(DetectionResolverMode.DIRECT, config.resolverMode)
        assertEquals(listOf("77.88.8.8"), config.directDnsServers)
    }

    @Test
    fun `doh active dns settings map to doh mode`() {
        val config =
            DetectionResolverConfig.fromActiveDnsSettings(
                activeDnsSettings(
                    mode = DnsModeEncrypted,
                    providerId = DnsProviderCloudflare,
                    protocol = EncryptedDnsProtocolDoh,
                    host = "cloudflare-dns.com",
                    bootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
                    dohUrl = "https://cloudflare-dns.com/dns-query",
                ),
            )

        assertEquals(DetectionResolverMode.DOH, config.resolverMode)
        assertEquals("https://cloudflare-dns.com/dns-query", config.dohPreset.dohUrl)
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), config.dohBootstrapIps)
    }

    @Test
    fun `unsupported encrypted dns settings fall back to system mode`() {
        val config =
            DetectionResolverConfig.fromActiveDnsSettings(
                activeDnsSettings(
                    mode = DnsModeEncrypted,
                    providerId = DnsProviderCustom,
                    protocol = EncryptedDnsProtocolDot,
                    host = "dns.example",
                    bootstrapIps = listOf("192.0.2.1"),
                    dohUrl = "",
                ),
            )

        assertEquals(DetectionResolverMode.SYSTEM, config.resolverMode)
    }

    private fun activeDnsSettings(
        mode: String,
        providerId: String,
        dnsIp: String = "",
        protocol: String = "",
        host: String = "",
        bootstrapIps: List<String> = emptyList(),
        dohUrl: String = "",
    ): ActiveDnsSettings =
        ActiveDnsSettings(
            mode = mode,
            providerId = providerId,
            dnsIp = dnsIp,
            encryptedDnsProtocol = protocol,
            encryptedDnsHost = host,
            encryptedDnsPort = 443,
            encryptedDnsTlsServerName = host,
            encryptedDnsBootstrapIps = bootstrapIps,
            encryptedDnsDohUrl = dohUrl,
            encryptedDnsDnscryptProviderName = "",
            encryptedDnsDnscryptPublicKey = "",
        )
}
