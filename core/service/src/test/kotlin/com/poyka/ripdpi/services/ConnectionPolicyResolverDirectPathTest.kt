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
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.PreferredEdgeCandidate
import com.poyka.ripdpi.data.PreferredEdgeIpVersionV4
import com.poyka.ripdpi.data.PreferredEdgeTransportTcp
import com.poyka.ripdpi.data.PreferredStack
import com.poyka.ripdpi.data.QuicMode
import com.poyka.ripdpi.data.ServerCapabilityObservation
import com.poyka.ripdpi.data.TcpFamily
import com.poyka.ripdpi.data.TransportPolicy
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
class ConnectionPolicyResolverDirectPathTest {
    @Test
    fun `resolver injects direct path capabilities into runtime context for startup policy`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val capabilityStore = TestServerCapabilityStore()
            val now = System.currentTimeMillis()
            seedTwoDirectPathObservations(capabilityStore, fingerprint, now)
            val resolver = buildResolver(fingerprint, capabilityStore)

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
            assertEquals(now, softDisable?.updatedAt)

            assertNotNull(noDirect)
            assertEquals("example.org:443", noDirect?.authority)
            assertEquals("feedface", noDirect?.ipSetDigest)
            assertEquals(QuicMode.HARD_DISABLE, noDirect?.quicMode)
            assertEquals(TcpFamily.REC_PRE_SNI, noDirect?.tcpFamily)
            assertEquals(DirectModeOutcome.NO_DIRECT_SOLUTION, noDirect?.outcome)
            assertEquals(DirectTransportClass.IP_BLOCK_SUSPECT, noDirect?.transportClass)
            assertEquals(DirectModeReasonCode.IP_BLOCKED, noDirect?.reasonCode)
            assertEquals(now + 60_000L, noDirect?.cooldownUntil)
            assertEquals(now, noDirect?.updatedAt)
        }

    @Test
    fun `resolver excludes unconfirmed direct path capabilities from runtime context`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val capabilityStore = TestServerCapabilityStore()
            capabilityStore.rememberDirectPathObservation(
                fingerprint = fingerprint,
                authority = "Example.org:443",
                observation =
                    ServerCapabilityObservation(
                        transportPolicy =
                            TransportPolicy(
                                quicMode = QuicMode.SOFT_DISABLE,
                                preferredStack = PreferredStack.H2,
                                dnsMode = DnsMode.SYSTEM,
                                tcpFamily = TcpFamily.NONE,
                                outcome = DirectModeOutcome.TRANSPARENT_OK,
                            ),
                        ipSetDigest = "deadbeef",
                        transportClass = DirectTransportClass.QUIC_BLOCK_SUSPECT,
                        reasonCode = DirectModeReasonCode.QUIC_BLOCKED,
                    ),
                recordedAt = 123L,
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

            assertTrue(
                uiPreferences
                    ?.runtimeContext
                    ?.directPathCapabilities
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun `resolver excludes stale or over-failed direct path capabilities from runtime context`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val capabilityStore = TestServerCapabilityStore()
            capabilityStore.rememberDirectPathObservation(
                fingerprint = fingerprint,
                authority = "stale.example:443",
                observation =
                    ServerCapabilityObservation(
                        transportPolicy =
                            TransportPolicy(
                                quicMode = QuicMode.SOFT_DISABLE,
                                preferredStack = PreferredStack.H2,
                                dnsMode = DnsMode.SYSTEM,
                                tcpFamily = TcpFamily.NONE,
                                outcome = DirectModeOutcome.TRANSPARENT_OK,
                            ),
                        ipSetDigest = "stale",
                        policyConfirmedAt = 1L,
                    ),
                recordedAt = 1L,
            )
            capabilityStore.rememberDirectPathObservation(
                fingerprint = fingerprint,
                authority = "failed.example:443",
                observation =
                    ServerCapabilityObservation(
                        transportPolicy =
                            TransportPolicy(
                                quicMode = QuicMode.SOFT_DISABLE,
                                preferredStack = PreferredStack.H2,
                                dnsMode = DnsMode.SYSTEM,
                                tcpFamily = TcpFamily.NONE,
                                outcome = DirectModeOutcome.TRANSPARENT_OK,
                            ),
                        ipSetDigest = "failed",
                        policyConfirmedAt = System.currentTimeMillis(),
                        policyFailureCount = 3,
                    ),
                recordedAt = System.currentTimeMillis(),
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

            assertTrue(
                uiPreferences
                    ?.runtimeContext
                    ?.directPathCapabilities
                    .orEmpty()
                    .isEmpty(),
            )
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

    private suspend fun seedTwoDirectPathObservations(
        store: TestServerCapabilityStore,
        fingerprint: NetworkFingerprint,
        now: Long,
    ) {
        store.rememberDirectPathObservation(
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
                    policyConfirmedAt = now,
                    ipSetDigest = "deadbeef",
                    dnsClassification = DirectDnsClassification.POISONED,
                    transportClass = DirectTransportClass.QUIC_BLOCK_SUSPECT,
                    reasonCode = DirectModeReasonCode.QUIC_BLOCKED,
                ),
            recordedAt = now,
        )
        store.rememberDirectPathObservation(
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
                    policyConfirmedAt = now,
                    ipSetDigest = "feedface",
                    transportClass = DirectTransportClass.IP_BLOCK_SUSPECT,
                    reasonCode = DirectModeReasonCode.IP_BLOCKED,
                    cooldownUntil = now + 60_000L,
                ),
            recordedAt = now,
        )
    }

    private fun buildResolver(
        fingerprint: NetworkFingerprint,
        capabilityStore: TestServerCapabilityStore,
    ): DefaultConnectionPolicyResolver =
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
