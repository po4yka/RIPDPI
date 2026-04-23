package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.decodeRipDpiProxyUiPreferences
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AsnRoutingMapCatalog
import com.poyka.ripdpi.data.AsnRoutingMapEntry
import com.poyka.ripdpi.data.DirectDnsClassification
import com.poyka.ripdpi.data.DirectModeOutcome
import com.poyka.ripdpi.data.DirectModeReasonCode
import com.poyka.ripdpi.data.DirectTransportClass
import com.poyka.ripdpi.data.DnsMode
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
import com.poyka.ripdpi.data.PreferredStack
import com.poyka.ripdpi.data.QuicMode
import com.poyka.ripdpi.data.ServerCapabilityObservation
import com.poyka.ripdpi.data.TcpFamily
import com.poyka.ripdpi.data.TransportPolicy
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
                    antiCorrelationRoutingPolicy = antiCorrelationRoutingPolicy(),
                    rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
                    startupDnsProbe = VpnStartupDnsProbe(),
                    rootHelperManager = RootHelperManager(),
                    serverCapabilityStore = TestServerCapabilityStore(),
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

    @Test
    fun `resolver injects direct path capabilities into runtime context for startup policy`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val capabilityStore = TestServerCapabilityStore()
            capabilityStore.rememberDirectPathObservation(
                fingerprint = fingerprint,
                authority = "Example.org:443",
                observation =
                    ServerCapabilityObservation(
                        quicUsable = false,
                        udpUsable = false,
                        fallbackRequired = true,
                        repeatedHandshakeFailureClass = "tcp_reset",
                        transportPolicy =
                            TransportPolicy(
                                quicMode = QuicMode.SOFT_DISABLE,
                                preferredStack = PreferredStack.H2,
                                dnsMode = DnsMode.SYSTEM,
                                tcpFamily = TcpFamily.NONE,
                                outcome = DirectModeOutcome.TRANSPARENT_OK,
                            ),
                        ipSetDigest = "deadbeef",
                        dnsClassification = DirectDnsClassification.POISONED,
                        transportClass = DirectTransportClass.QUIC_BLOCK_SUSPECT,
                        reasonCode = DirectModeReasonCode.QUIC_BLOCKED,
                    ),
                recordedAt = 123L,
            )
            capabilityStore.rememberDirectPathObservation(
                fingerprint = fingerprint,
                authority = "Example.org:443",
                observation =
                    ServerCapabilityObservation(
                        quicUsable = false,
                        udpUsable = false,
                        fallbackRequired = true,
                        repeatedHandshakeFailureClass = "tls_alert",
                        transportPolicy =
                            TransportPolicy(
                                quicMode = QuicMode.HARD_DISABLE,
                                preferredStack = PreferredStack.H2,
                                dnsMode = DnsMode.SYSTEM,
                                tcpFamily = TcpFamily.REC_PRE_SNI,
                                outcome = DirectModeOutcome.NO_DIRECT_SOLUTION,
                            ),
                        ipSetDigest = "feedface",
                        transportClass = DirectTransportClass.IP_BLOCK_SUSPECT,
                        reasonCode = DirectModeReasonCode.IP_BLOCKED,
                        cooldownUntil = 999L,
                    ),
                recordedAt = 124L,
            )
            val resolver =
                DefaultConnectionPolicyResolver(
                    context = RuntimeEnvironment.getApplication(),
                    appSettingsRepository = TestAppSettingsRepository(AppSettingsSerializer.defaultValue),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
                    networkEdgePreferenceStore = TestNetworkEdgePreferenceStore(),
                    antiCorrelationRoutingPolicy = antiCorrelationRoutingPolicy(),
                    rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
                    startupDnsProbe = VpnStartupDnsProbe(),
                    rootHelperManager = RootHelperManager(),
                    serverCapabilityStore = capabilityStore,
                )

            val resolution = resolver.resolve(mode = Mode.Proxy)
            val uiPreferences = decodeRipDpiProxyUiPreferences(resolution.proxyPreferences.toNativeConfigJson())
            val capabilities = uiPreferences?.runtimeContext?.directPathCapabilities.orEmpty()
            val softDisable = capabilities.firstOrNull { it.ipSetDigest == "deadbeef" }
            val noDirect = capabilities.firstOrNull { it.ipSetDigest == "feedface" }

            assertEquals(2, capabilities.size)
            assertNotNull(softDisable)
            assertEquals("example.org:443", softDisable?.authority)
            assertEquals(false, softDisable?.quicUsable)
            assertEquals(false, softDisable?.udpUsable)
            assertEquals(true, softDisable?.fallbackRequired)
            assertEquals("tcp_reset", softDisable?.repeatedHandshakeFailureClass)
            assertEquals(QuicMode.SOFT_DISABLE, softDisable?.quicMode)
            assertEquals(DirectDnsClassification.POISONED, softDisable?.dnsClassification)
            assertEquals(PreferredStack.H2, softDisable?.preferredStack)
            assertEquals(DnsMode.SYSTEM, softDisable?.dnsMode)
            assertEquals(TcpFamily.NONE, softDisable?.tcpFamily)
            assertEquals(DirectModeOutcome.TRANSPARENT_OK, softDisable?.outcome)
            assertEquals(DirectTransportClass.QUIC_BLOCK_SUSPECT, softDisable?.transportClass)
            assertEquals(DirectModeReasonCode.QUIC_BLOCKED, softDisable?.reasonCode)
            assertEquals(123L, softDisable?.updatedAt)

            assertNotNull(noDirect)
            assertEquals("example.org:443", noDirect?.authority)
            assertEquals("feedface", noDirect?.ipSetDigest)
            assertEquals(QuicMode.HARD_DISABLE, noDirect?.quicMode)
            assertEquals(TcpFamily.REC_PRE_SNI, noDirect?.tcpFamily)
            assertEquals(DirectModeOutcome.NO_DIRECT_SOLUTION, noDirect?.outcome)
            assertEquals(DirectTransportClass.IP_BLOCK_SUSPECT, noDirect?.transportClass)
            assertEquals(DirectModeReasonCode.IP_BLOCKED, noDirect?.reasonCode)
            assertEquals(999L, noDirect?.cooldownUntil)
            assertEquals(124L, noDirect?.updatedAt)
        }

    @Test
    fun `resolver boosts global CDN preferred edges when anti correlation is enabled`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val edgeStore = TestNetworkEdgePreferenceStore()
            edgeStore.rememberPreferredEdges(
                fingerprint = fingerprint,
                host = "video.example.org",
                transportKind = PreferredEdgeTransportTcp,
                edges =
                    listOf(
                        PreferredEdgeCandidate(
                            ip = "203.0.113.10",
                            transportKind = PreferredEdgeTransportTcp,
                            ipVersion = PreferredEdgeIpVersionV4,
                            successCount = 1,
                            cdnProvider = "Yandex",
                        ),
                        PreferredEdgeCandidate(
                            ip = "203.0.113.11",
                            transportKind = PreferredEdgeTransportTcp,
                            ipVersion = PreferredEdgeIpVersionV4,
                            successCount = 1,
                            cdnProvider = "Cloudflare",
                        ),
                    ),
                recordedAt = 100L,
            )
            val settings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setAntiCorrelationEnabled(true)
                    .build()
            val resolver =
                DefaultConnectionPolicyResolver(
                    context = RuntimeEnvironment.getApplication(),
                    appSettingsRepository = TestAppSettingsRepository(settings),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
                    networkEdgePreferenceStore = edgeStore,
                    antiCorrelationRoutingPolicy = antiCorrelationRoutingPolicy(),
                    rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
                    startupDnsProbe = VpnStartupDnsProbe(),
                    rootHelperManager = RootHelperManager(),
                    serverCapabilityStore = TestServerCapabilityStore(),
                )

            val resolution = resolver.resolve(mode = Mode.Proxy)
            val uiPreferences = decodeRipDpiProxyUiPreferences(resolution.proxyPreferences.toNativeConfigJson())

            assertNotNull(uiPreferences)
            assertEquals(
                listOf("203.0.113.11"),
                uiPreferences
                    ?.runtimeContext
                    ?.preferredEdges
                    ?.get("video.example.org")
                    ?.map { it.ip },
            )
        }

    @Test
    fun `resolver drops domestic CDN edge pinning when no foreign CDN edge exists`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val edgeStore = TestNetworkEdgePreferenceStore()
            edgeStore.rememberPreferredEdges(
                fingerprint = fingerprint,
                host = "cdn.example.org",
                transportKind = PreferredEdgeTransportTcp,
                edges =
                    listOf(
                        PreferredEdgeCandidate(
                            ip = "203.0.113.10",
                            transportKind = PreferredEdgeTransportTcp,
                            ipVersion = PreferredEdgeIpVersionV4,
                            successCount = 3,
                            cdnProvider = "Yandex",
                        ),
                    ),
                recordedAt = 100L,
            )
            val settings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setAntiCorrelationEnabled(true)
                    .build()
            val resolver =
                DefaultConnectionPolicyResolver(
                    context = RuntimeEnvironment.getApplication(),
                    appSettingsRepository = TestAppSettingsRepository(settings),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
                    networkEdgePreferenceStore = edgeStore,
                    antiCorrelationRoutingPolicy = antiCorrelationRoutingPolicy(),
                    rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
                    startupDnsProbe = VpnStartupDnsProbe(),
                    rootHelperManager = RootHelperManager(),
                    serverCapabilityStore = TestServerCapabilityStore(),
                )

            val resolution = resolver.resolve(mode = Mode.Proxy)
            val uiPreferences = decodeRipDpiProxyUiPreferences(resolution.proxyPreferences.toNativeConfigJson())

            assertTrue(
                uiPreferences
                    ?.runtimeContext
                    ?.preferredEdges
                    ?.containsKey("cdn.example.org") != true,
            )
        }

    @Test
    fun `resolver drops domestic edge when no foreign cdn candidate exists`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val edgeStore = TestNetworkEdgePreferenceStore()
            edgeStore.rememberPreferredEdges(
                fingerprint = fingerprint,
                host = "music.example.org",
                transportKind = PreferredEdgeTransportTcp,
                edges =
                    listOf(
                        PreferredEdgeCandidate(
                            ip = "203.0.113.10",
                            transportKind = PreferredEdgeTransportTcp,
                            ipVersion = PreferredEdgeIpVersionV4,
                            successCount = 1,
                            cdnProvider = "Yandex",
                        ),
                    ),
                recordedAt = 100L,
            )
            val settings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setAntiCorrelationEnabled(true)
                    .build()
            val resolver =
                DefaultConnectionPolicyResolver(
                    context = RuntimeEnvironment.getApplication(),
                    appSettingsRepository = TestAppSettingsRepository(settings),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
                    networkEdgePreferenceStore = edgeStore,
                    antiCorrelationRoutingPolicy = antiCorrelationRoutingPolicy(),
                    rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
                    startupDnsProbe = VpnStartupDnsProbe(),
                    rootHelperManager = RootHelperManager(),
                    serverCapabilityStore = TestServerCapabilityStore(),
                )

            val resolution = resolver.resolve(mode = Mode.Proxy)
            val uiPreferences = decodeRipDpiProxyUiPreferences(resolution.proxyPreferences.toNativeConfigJson())

            assertTrue(
                uiPreferences
                    ?.runtimeContext
                    ?.preferredEdges
                    ?.containsKey("music.example.org") != true,
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

    private fun antiCorrelationRoutingPolicy(): AntiCorrelationRoutingPolicy =
        DefaultAntiCorrelationRoutingPolicy(
            asnRoutingCatalogProvider =
                object : AsnRoutingCatalogProvider {
                    override fun load(): AsnRoutingMapCatalog =
                        AsnRoutingMapCatalog(
                            entries =
                                listOf(
                                    AsnRoutingMapEntry(
                                        asn = 13238,
                                        label = "Yandex",
                                        country = "RU",
                                        cdn = true,
                                    ),
                                ),
                        )
                },
        )
}
