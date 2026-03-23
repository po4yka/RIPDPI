package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.DEFAULT_TUN2SOCKS_TUNNEL_MTU
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RipDpiVpnServiceConfigTest {
    @Test
    fun buildTun2SocksConfigIncludesIpv6TunnelAddressWhenEnabled() {
        val config =
            RipDpiVpnService.buildTun2SocksConfig(
                activeDns = plainDns("2606:4700:4700::1111"),
                overrideReason = null,
                socks5Port = 1080,
                ipv6Enabled = true,
            )

        assertEquals("10.10.10.10/32", config.tunnelIpv4)
        assertEquals("fd00::1/128", config.tunnelIpv6)
        assertEquals(DEFAULT_TUN2SOCKS_TUNNEL_MTU, config.tunnelMtu)
        assertEquals("udp", config.socks5Udp)
        assertEquals(1080, config.socks5Port)
        assertNull(config.mapdnsAddress)
    }

    @Test
    fun buildTun2SocksConfigUsesMapDnsAndLeavesIpv6UnsetWhenDisabled() {
        val config =
            RipDpiVpnService.buildTun2SocksConfig(
                activeDns = encryptedDns(),
                overrideReason = "dns_probe_failed",
                socks5Port = 2080,
                ipv6Enabled = false,
            )

        assertEquals("10.10.10.10/32", config.tunnelIpv4)
        assertNull(config.tunnelIpv6)
        assertEquals(DEFAULT_TUN2SOCKS_TUNNEL_MTU, config.tunnelMtu)
        assertEquals("udp", config.socks5Udp)
        assertEquals("198.18.0.53", config.mapdnsAddress)
        assertEquals(53, config.mapdnsPort)
        assertEquals("198.18.0.0", config.mapdnsNetwork)
        assertEquals("255.254.0.0", config.mapdnsNetmask)
        assertEquals("cloudflare", config.encryptedDnsResolverId)
        assertTrue(config.resolverFallbackActive == true)
        assertEquals("dns_probe_failed", config.resolverFallbackReason)
    }

    private fun plainDns(dnsIp: String): ActiveDnsSettings =
        ActiveDnsSettings(
            mode = DnsModePlainUdp,
            providerId = "custom",
            dnsIp = dnsIp,
            encryptedDnsProtocol = "",
            encryptedDnsHost = "",
            encryptedDnsPort = 0,
            encryptedDnsTlsServerName = "",
            encryptedDnsBootstrapIps = emptyList(),
            encryptedDnsDohUrl = "",
            encryptedDnsDnscryptProviderName = "",
            encryptedDnsDnscryptPublicKey = "",
        )

    private fun encryptedDns(): ActiveDnsSettings =
        ActiveDnsSettings(
            mode = DnsModeEncrypted,
            providerId = "cloudflare",
            dnsIp = "1.1.1.1",
            encryptedDnsProtocol = "doh",
            encryptedDnsHost = "cloudflare-dns.com",
            encryptedDnsPort = 443,
            encryptedDnsTlsServerName = "cloudflare-dns.com",
            encryptedDnsBootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
            encryptedDnsDohUrl = "https://cloudflare-dns.com/dns-query",
            encryptedDnsDnscryptProviderName = "",
            encryptedDnsDnscryptPublicKey = "",
        )
}
