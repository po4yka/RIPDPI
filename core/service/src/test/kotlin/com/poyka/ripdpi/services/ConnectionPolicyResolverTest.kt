package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderGoogle
import com.poyka.ripdpi.data.DnsProviderQuad9
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.EncryptedDnsProtocolDot
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PreferredEdgeCandidate
import com.poyka.ripdpi.data.PreferredEdgeIpVersionV4
import com.poyka.ripdpi.data.PreferredEdgeTransportTcp
import com.poyka.ripdpi.data.VpnDnsPolicyJson
import com.poyka.ripdpi.data.toTemporaryResolverOverride
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionPolicyResolverTest {
    @Test
    fun `temporary override beats remembered vpn dns policy`() =
        runTest {
            val override =
                quad9DotPath().toTemporaryResolverOverride(
                    reason = "vpn_encrypted_dns_auto_failover: resolver timeout",
                    appliedAt = 10L,
                )
            val dnsResolution = resolveEffectiveDns(encryptedGoogleSettings(), override)

            val selection =
                resolveVpnDnsSelection(
                    mode = Mode.VPN,
                    baseDns = dnsResolution.activeDns,
                    preferredPath = quad9DotPath(),
                    rememberedVpnDnsPolicy = cloudflareRememberedPolicy(),
                    resolverOverride = dnsResolution.override,
                )

            assertEquals(override.toActiveDnsSettings(), selection.activeDns)
            assertNull(selection.rememberedVpnDnsPolicy)
        }

    @Test
    fun `preferred encrypted path beats remembered vpn dns policy`() =
        runTest {
            val selection =
                resolveVpnDnsSelection(
                    mode = Mode.VPN,
                    baseDns = resolveEffectiveDns(encryptedGoogleSettings(), override = null).activeDns,
                    preferredPath = quad9DotPath(),
                    rememberedVpnDnsPolicy = cloudflareRememberedPolicy(),
                )

            assertEquals(quad9DotPath().toActiveDnsSettings(), selection.activeDns)
            assertEquals(quad9DotPath().pathKey(), selection.preferredPath?.pathKey())
            assertNull(selection.rememberedVpnDnsPolicy)
        }

    @Test
    fun `plain udp vpn dns selection applies preferred path when available`() =
        runTest {
            val selection =
                resolveVpnDnsSelection(
                    mode = Mode.VPN,
                    baseDns = resolveEffectiveDns(plainUdpSettings(), override = null).activeDns,
                    preferredPath = quad9DotPath(),
                )

            assertEquals(quad9DotPath().toActiveDnsSettings(), selection.activeDns)
            assertEquals(quad9DotPath().pathKey(), selection.preferredPath?.pathKey())
            assertNull(selection.rememberedVpnDnsPolicy)
        }

    @Test
    fun `plain udp vpn dns selection remains unchanged without preferred path`() =
        runTest {
            val selection =
                resolveVpnDnsSelection(
                    mode = Mode.VPN,
                    baseDns = resolveEffectiveDns(plainUdpSettings(), override = null).activeDns,
                )

            assertTrue(selection.activeDns.isPlainUdp)
            assertEquals("9.9.9.9", selection.activeDns.dnsIp)
            assertNull(selection.rememberedVpnDnsPolicy)
        }

    @Test
    fun `proxy mode ignores vpn-only preferred and remembered dns state`() =
        runTest {
            val baseDns = resolveEffectiveDns(encryptedGoogleSettings(), override = null).activeDns

            val selection =
                resolveVpnDnsSelection(
                    mode = Mode.Proxy,
                    baseDns = baseDns,
                    preferredPath = quad9DotPath(),
                    rememberedVpnDnsPolicy = cloudflareRememberedPolicy(),
                )

            assertEquals(baseDns, selection.activeDns)
            assertNull(selection.preferredPath)
            assertNull(selection.rememberedVpnDnsPolicy)
        }

    @Test
    fun `resolver injects preferred edges into runtime context for startup policy`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val edgeStore = TestNetworkEdgePreferenceStore()
            edgeStore.rememberPreferredEdges(
                fingerprint = fingerprint,
                host = "example.org",
                transportKind = PreferredEdgeTransportTcp,
                edges =
                    listOf(
                        PreferredEdgeCandidate(
                            ip = "203.0.113.10",
                            transportKind = PreferredEdgeTransportTcp,
                            ipVersion = PreferredEdgeIpVersionV4,
                            successCount = 2,
                        ),
                    ),
                recordedAt = 100L,
            )
            val resolver =
                DefaultConnectionPolicyResolver(
                    context = RuntimeEnvironment.getApplication(),
                    appSettingsRepository = TestAppSettingsRepository(AppSettingsSerializer.defaultValue),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
                    networkEdgePreferenceStore = edgeStore,
                    rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
                    startupDnsProbe = VpnStartupDnsProbe(),
                    rootHelperManager = RootHelperManager(),
                )

            val resolution = resolver.resolve(mode = Mode.Proxy)
            val uiPreferences = decodeRipDpiProxyUiPreferences(resolution.proxyPreferences.toNativeConfigJson())

            assertNotNull(uiPreferences)
            assertEquals(
                listOf("203.0.113.10"),
                uiPreferences
                    ?.runtimeContext
                    ?.preferredEdges
                    ?.get("example.org")
                    ?.map { it.ip },
            )
        }

    private fun encryptedGoogleSettings() =
        AppSettingsSerializer.defaultValue
            .toBuilder()
            .setDnsMode(DnsModeEncrypted)
            .setDnsProviderId(DnsProviderGoogle)
            .setDnsIp("8.8.8.8")
            .setEncryptedDnsProtocol(EncryptedDnsProtocolDoh)
            .setEncryptedDnsHost("dns.google")
            .setEncryptedDnsPort(443)
            .setEncryptedDnsTlsServerName("dns.google")
            .clearEncryptedDnsBootstrapIps()
            .addAllEncryptedDnsBootstrapIps(listOf("8.8.8.8", "8.8.4.4"))
            .setEncryptedDnsDohUrl("https://dns.google/dns-query")
            .build()

    private fun plainUdpSettings() =
        AppSettingsSerializer.defaultValue
            .toBuilder()
            .setDnsMode(DnsModePlainUdp)
            .setDnsIp("9.9.9.9")
            .build()

    private fun cloudflareRememberedPolicy(): VpnDnsPolicyJson =
        VpnDnsPolicyJson(
            mode = DnsModeEncrypted,
            providerId = DnsProviderCloudflare,
            dnsIp = "1.1.1.1",
            encryptedDnsProtocol = EncryptedDnsProtocolDoh,
            encryptedDnsHost = "cloudflare-dns.com",
            encryptedDnsPort = 443,
            encryptedDnsTlsServerName = "cloudflare-dns.com",
            encryptedDnsBootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
            encryptedDnsDohUrl = "https://cloudflare-dns.com/dns-query",
        )

    private fun quad9DotPath(): EncryptedDnsPathCandidate =
        EncryptedDnsPathCandidate(
            resolverId = DnsProviderQuad9,
            resolverLabel = "Quad9",
            protocol = EncryptedDnsProtocolDot,
            host = "dns.quad9.net",
            port = 853,
            tlsServerName = "dns.quad9.net",
            bootstrapIps = listOf("9.9.9.9", "149.112.112.112"),
        )
}
