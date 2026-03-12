package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.activeDnsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnResolverRuntimeTest {
    @Test
    fun `resolver override takes precedence over persisted settings`() {
        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDnsMode(DnsModePlainUdp)
                .setDnsIp("9.9.9.9")
                .build()

        val override =
            TemporaryResolverOverride(
                resolverId = DnsProviderCloudflare,
                protocol = EncryptedDnsProtocolDoh,
                host = "cloudflare-dns.com",
                port = 443,
                tlsServerName = "cloudflare-dns.com",
                bootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
                dohUrl = "https://cloudflare-dns.com/dns-query",
                dnscryptProviderName = "",
                dnscryptPublicKey = "",
                reason = "UDP DNS showed udp_blocked",
                appliedAt = 10L,
            )

        val resolution = resolveEffectiveDns(settings, override)

        assertEquals(DnsModeEncrypted, resolution.activeDns.mode)
        assertEquals(DnsProviderCloudflare, resolution.activeDns.providerId)
        assertEquals("1.1.1.1", resolution.activeDns.dnsIp)
        assertEquals("UDP DNS showed udp_blocked", resolution.override?.reason)
        assertFalse(resolution.shouldClearOverride)
    }

    @Test
    fun `matching persisted encrypted settings clear temporary override`() {
        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDnsMode(DnsModeEncrypted)
                .setDnsProviderId(DnsProviderCloudflare)
                .setDnsIp("1.1.1.1")
                .setEncryptedDnsProtocol(EncryptedDnsProtocolDoh)
                .setEncryptedDnsHost("cloudflare-dns.com")
                .setEncryptedDnsPort(443)
                .setEncryptedDnsTlsServerName("cloudflare-dns.com")
                .addAllEncryptedDnsBootstrapIps(listOf("1.1.1.1", "1.0.0.1"))
                .setEncryptedDnsDohUrl("https://cloudflare-dns.com/dns-query")
                .build()

        val override =
            TemporaryResolverOverride(
                resolverId = DnsProviderCloudflare,
                protocol = EncryptedDnsProtocolDoh,
                host = "cloudflare-dns.com",
                port = 443,
                tlsServerName = "cloudflare-dns.com",
                bootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
                dohUrl = "https://cloudflare-dns.com/dns-query",
                dnscryptProviderName = "",
                dnscryptPublicKey = "",
                reason = "temporary override",
                appliedAt = 10L,
            )

        val resolution = resolveEffectiveDns(settings, override)

        assertEquals(DnsProviderCloudflare, resolution.activeDns.providerId)
        assertTrue(resolution.shouldClearOverride)
        assertNull(resolution.override)
    }

    @Test
    fun `resolver refresh requests tunnel rebuild when signature changes`() {
        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDnsMode(DnsModePlainUdp)
                .setDnsIp("9.9.9.9")
                .build()

        val override =
            TemporaryResolverOverride(
                resolverId = DnsProviderCloudflare,
                protocol = EncryptedDnsProtocolDoh,
                host = "cloudflare-dns.com",
                port = 443,
                tlsServerName = "cloudflare-dns.com",
                bootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
                dohUrl = "https://cloudflare-dns.com/dns-query",
                dnscryptProviderName = "",
                dnscryptPublicKey = "",
                reason = "temporary override",
                appliedAt = 10L,
            )

        val initialSignature = dnsSignature(settings.activeDnsSettings(), overrideReason = null)
        val plan =
            planResolverRefresh(
                settings = settings,
                override = override,
                currentSignature = initialSignature,
                tunnelRunning = true,
            )

        assertTrue(plan.requiresTunnelRebuild)
        assertEquals(DnsProviderCloudflare, plan.resolution.activeDns.providerId)
    }

    @Test
    fun `resolver refresh does not rebuild inactive tunnel`() {
        val settings = AppSettingsSerializer.defaultValue

        val plan =
            planResolverRefresh(
                settings = settings,
                override = null,
                currentSignature = null,
                tunnelRunning = false,
            )

        assertFalse(plan.requiresTunnelRebuild)
        assertFalse(plan.resolution.shouldClearOverride)
    }

    @Test
    fun `network handover classification distinguishes transport refresh and loss`() {
        val wifi =
            NetworkFingerprint(
                transportLabel = "wifi",
                interfaceName = "wlan0",
                dnsServers = listOf("1.1.1.1"),
            )
        val refreshedWifi =
            NetworkFingerprint(
                transportLabel = "wifi",
                interfaceName = "wlan0",
                dnsServers = listOf("8.8.8.8"),
            )
        val cellular =
            NetworkFingerprint(
                transportLabel = "cellular",
                interfaceName = "rmnet0",
                dnsServers = listOf("1.1.1.1"),
            )

        assertEquals("link_refresh", classifyNetworkHandover(wifi, refreshedWifi))
        assertEquals("transport_switch", classifyNetworkHandover(wifi, cellular))
        assertEquals("connectivity_loss", classifyNetworkHandover(wifi, null))
        assertNull(classifyNetworkHandover(wifi, wifi))
    }
}
