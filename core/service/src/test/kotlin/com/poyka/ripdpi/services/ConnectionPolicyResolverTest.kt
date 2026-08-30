package com.poyka.ripdpi.services

import android.content.Context
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.RipDpiWsTunnelConfig
import com.poyka.ripdpi.core.awgConfigOrNull
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
import com.poyka.ripdpi.data.DnsProviderAdGuard
import com.poyka.ripdpi.data.DnsProviderCloudflare
import com.poyka.ripdpi.data.DnsProviderDnsSb
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
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RememberedConnectionConcurrencyPolicyJson
import com.poyka.ripdpi.data.RootSettingsSection
import com.poyka.ripdpi.data.ServerCapabilityObservation
import com.poyka.ripdpi.data.TcpFamily
import com.poyka.ripdpi.data.TransportPolicy
import com.poyka.ripdpi.data.VpnDnsPolicyJson
import com.poyka.ripdpi.data.WsTunnelWorkerCredentialStore
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.builtInEncryptedDnsPathCandidates
import com.poyka.ripdpi.data.toTemporaryResolverOverride
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySnapshot
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
    fun `initial resolution fails when destination routing source is unavailable`() =
        runTest {
            val resolver =
                DefaultConnectionPolicyResolver(
                    context = RuntimeEnvironment.getApplication(),
                    appSettingsRepository = TestAppSettingsRepository(AppSettingsSerializer.defaultValue),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
                    networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
                    networkEdgePreferenceStore = TestNetworkEdgePreferenceStore(),
                    antiCorrelationRoutingPolicy = antiCorrelationRoutingPolicy(),
                    rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
                    rootHelperManager = RootHelperManager(),
                    environmentDetector = EnvironmentDetector(),
                    serverCapabilityStore = TestServerCapabilityStore(),
                    awgEgressSelectionProvider = StaticAwgEgressSelectionProvider(null),
                    destinationRoutingPolicySource =
                        DestinationRoutingPolicySource {
                            DestinationRoutingPolicySnapshot.Unavailable("rule_source_unavailable")
                        },
                    proxySessionSecretResolver = ProxySessionSecretResolver(EmptyWsTunnelWorkerCredentialStore),
                )

            val failure = runCatching { resolver.resolve(mode = Mode.VPN) }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message?.contains("rule_source_unavailable") == true)
        }

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
    fun `vpn cold start without network fingerprint uses encrypted dns default`() =
        runTest {
            val resolver =
                DefaultConnectionPolicyResolver(
                    context = RuntimeEnvironment.getApplication(),
                    appSettingsRepository = TestAppSettingsRepository(plainUdpSettings()),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(null),
                    networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
                    networkEdgePreferenceStore = TestNetworkEdgePreferenceStore(),
                    antiCorrelationRoutingPolicy = antiCorrelationRoutingPolicy(),
                    rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
                    rootHelperManager = RootHelperManager(),
                    environmentDetector = EnvironmentDetector(),
                    serverCapabilityStore = TestServerCapabilityStore(),
                    awgEgressSelectionProvider = StaticAwgEgressSelectionProvider(null),
                    destinationRoutingPolicySource = EmptyDestinationRoutingPolicySource,
                    proxySessionSecretResolver = ProxySessionSecretResolver(EmptyWsTunnelWorkerCredentialStore),
                )

            val resolution = resolver.resolve(mode = Mode.VPN)

            assertEquals(DnsProviderAdGuard, resolution.activeDns.providerId)
            assertEquals(EncryptedDnsProtocolDoh, resolution.activeDns.encryptedDnsProtocol)
            assertEquals("dns.adguard-dns.com", resolution.activeDns.encryptedDnsHost)
            assertEquals("", resolution.destinationRoutingDigest)
            assertEquals("", resolution.splitStrictDnsPolicy?.canonicalDigest)
            assertEquals(
                buildConnectionPolicySignature(
                    mode = Mode.VPN,
                    proxyPreferences = resolution.proxyPreferences,
                    activeDns = resolution.activeDns,
                    resolverFallbackReason = resolution.resolverFallbackReason,
                    matchedPolicy = null,
                ),
                resolution.policySignature,
            )
        }

    @Test
    fun `learned Cloudflare preference cannot suppress encrypted cold start fallback`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val preferences = TestNetworkDnsPathPreferenceStore()
            preferences.rememberPreferredPath(
                fingerprint,
                builtInEncryptedDnsPathCandidates().first { it.resolverId == DnsProviderCloudflare },
            )
            val selector = ConnectionPolicyDnsSelector(preferences, ResolverMappingPolicy(), ResolverMappingCache())

            val selection =
                selector.baselineSelection(
                    mode = Mode.VPN,
                    dnsResolution = resolveEffectiveDns(plainUdpSettings(), override = null),
                    networkScopeKey = fingerprint.scopeKey(),
                    directPathCapabilities = emptyList(),
                )

            assertEquals(DnsProviderAdGuard, selection.activeDns.providerId)
            assertEquals(EncryptedDnsProtocolDoh, selection.activeDns.encryptedDnsProtocol)
            assertNull(selection.preferredPath)
        }

    @Test
    fun `remembered Cloudflare policy cannot replace independent cold start DNS`() =
        runTest {
            listOf(DnsProviderCloudflare, "cloudflare-malware").forEach { providerId ->
                val rememberedStore =
                    TestRememberedNetworkPolicyStore().apply {
                        validatedMatch =
                            sampleRememberedPolicyEntity(mode = Mode.VPN).copy(
                                proxyConfigJson = RipDpiProxyUIPreferences().toNativeConfigJson(),
                                vpnDnsPolicyJson =
                                    Json.encodeToString(
                                        cloudflareRememberedPolicy().copy(providerId = providerId),
                                    ),
                            )
                    }
                val resolver =
                    DefaultConnectionPolicyResolver(
                        context = RuntimeEnvironment.getApplication(),
                        appSettingsRepository =
                            TestAppSettingsRepository(
                                plainUdpSettings()
                                    .toBuilder()
                                    .setNetworkStrategyMemoryEnabled(true)
                                    .build(),
                            ),
                        networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
                        networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
                        networkEdgePreferenceStore = TestNetworkEdgePreferenceStore(),
                        antiCorrelationRoutingPolicy = antiCorrelationRoutingPolicy(),
                        rememberedNetworkPolicyStore = rememberedStore,
                        rootHelperManager = RootHelperManager(),
                        environmentDetector = EnvironmentDetector(),
                        serverCapabilityStore = TestServerCapabilityStore(),
                        awgEgressSelectionProvider = StaticAwgEgressSelectionProvider(null),
                        destinationRoutingPolicySource = EmptyDestinationRoutingPolicySource,
                        proxySessionSecretResolver = ProxySessionSecretResolver(EmptyWsTunnelWorkerCredentialStore),
                    )

                val resolution = resolver.resolve(mode = Mode.VPN)

                assertEquals(true, resolution.rememberedPolicyAppliedByExactMatch)
                assertEquals(DnsProviderAdGuard, resolution.activeDns.providerId)
                assertEquals("dns.adguard-dns.com", resolution.activeDns.encryptedDnsHost)
            }
        }

    @Test
    fun `remembered Worker route uses the current configured endpoint and credential`() =
        runTest {
            val oldUrl = "https://old-worker.example/ws"
            val oldRef = "old-worker"
            val oldBearer = "old-secret"
            val newRef = "new-worker"
            val store =
                object : WsTunnelWorkerCredentialStore {
                    private val credentials = mapOf(oldRef to oldBearer, newRef to "new-secret")

                    override suspend fun load(credentialRef: String): String? = credentials[credentialRef]

                    override suspend fun save(
                        credentialRef: String,
                        bearer: String,
                    ) = Unit

                    override suspend fun clear(credentialRef: String) = Unit

                    override suspend fun clearAll() = Unit
                }
            val rememberedStore =
                TestRememberedNetworkPolicyStore().apply {
                    validatedMatch =
                        sampleRememberedPolicyEntity(mode = Mode.VPN).copy(
                            proxyConfigJson =
                                RipDpiProxyUIPreferences(
                                    wsTunnel =
                                        RipDpiWsTunnelConfig(
                                            enabled = true,
                                            mode = "always",
                                            cloudflareWorkerUrl = oldUrl,
                                            cloudflareWorkerCredentialRef = oldRef,
                                        ),
                                ).toNativeConfigJson(),
                        )
                }
            val newUrl = "https://new-worker.example/ws"
            val currentSettings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setWsTunnelEnabled(true)
                    .setWsTunnelMode("always")
                    .setWsTunnelWorkerUrl(newUrl)
                    .setWsTunnelWorkerCredentialRef(newRef)
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val resolver =
                DefaultConnectionPolicyResolver(
                    context = RuntimeEnvironment.getApplication(),
                    appSettingsRepository = TestAppSettingsRepository(currentSettings),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
                    networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
                    networkEdgePreferenceStore = TestNetworkEdgePreferenceStore(),
                    antiCorrelationRoutingPolicy = antiCorrelationRoutingPolicy(),
                    rememberedNetworkPolicyStore = rememberedStore,
                    rootHelperManager = RootHelperManager(),
                    environmentDetector = EnvironmentDetector(),
                    serverCapabilityStore = TestServerCapabilityStore(),
                    awgEgressSelectionProvider = StaticAwgEgressSelectionProvider(null),
                    destinationRoutingPolicySource = EmptyDestinationRoutingPolicySource,
                    proxySessionSecretResolver = ProxySessionSecretResolver(store),
                )

            val uiPreferences =
                decodeRipDpiProxyUiPreferences(
                    resolver.resolve(mode = Mode.VPN).proxyPreferences.toNativeConfigJson(),
                )

            assertEquals(newUrl, uiPreferences?.wsTunnel?.cloudflareWorkerUrl)
            assertEquals(newRef, uiPreferences?.wsTunnel?.cloudflareWorkerCredentialRef)
            assertEquals("new-secret", uiPreferences?.wsTunnel?.cloudflareWorkerBearer?.value)
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
                    rootHelperManager = RootHelperManager(),
                    environmentDetector = EnvironmentDetector(),
                    serverCapabilityStore = TestServerCapabilityStore(),
                    awgEgressSelectionProvider = StaticAwgEgressSelectionProvider(null),
                    destinationRoutingPolicySource = EmptyDestinationRoutingPolicySource,
                    proxySessionSecretResolver = ProxySessionSecretResolver(EmptyWsTunnelWorkerCredentialStore),
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
    fun `resolver starts root helper before building root mode preferences`() =
        runTest {
            val rootHelper = FakeRootHelperManager("/tmp/ripdpi-root-helper.sock")
            val resolver =
                DefaultConnectionPolicyResolver(
                    context = RuntimeEnvironment.getApplication(),
                    appSettingsRepository =
                        TestAppSettingsRepository(
                            AppSettingsSerializer.defaultValue
                                .toBuilder()
                                .setRootModeEnabled(true)
                                .build(),
                        ),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
                    networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
                    networkEdgePreferenceStore = TestNetworkEdgePreferenceStore(),
                    antiCorrelationRoutingPolicy = antiCorrelationRoutingPolicy(),
                    rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
                    rootHelperManager = rootHelper,
                    environmentDetector = EnvironmentDetector(),
                    serverCapabilityStore = TestServerCapabilityStore(),
                    awgEgressSelectionProvider = StaticAwgEgressSelectionProvider(null),
                    destinationRoutingPolicySource = EmptyDestinationRoutingPolicySource,
                    proxySessionSecretResolver = ProxySessionSecretResolver(EmptyWsTunnelWorkerCredentialStore),
                )

            val resolution = resolver.resolve(mode = Mode.Proxy)
            val uiPreferences = decodeRipDpiProxyUiPreferences(resolution.proxyPreferences.toNativeConfigJson())

            assertEquals(listOf(true), rootHelper.syncCalls)
            assertEquals("/tmp/ripdpi-root-helper.sock", rootHelper.socketPath)
            assertEquals("/tmp/ripdpi-root-helper.sock", uiPreferences?.rootHelperSocketPath)
        }

    @Test
    fun `retired remembered config schema records failure and falls back to baseline`() =
        runTest {
            val retiredConfig =
                RipDpiProxyUIPreferences()
                    .toNativeConfigJson()
                    .replace("\"schemaVersion\":2", "\"schemaVersion\":1")
            val rememberedStore =
                TestRememberedNetworkPolicyStore().apply {
                    validatedMatch =
                        sampleRememberedPolicyEntity(mode = Mode.VPN).copy(
                            proxyConfigJson = retiredConfig,
                        )
                }
            val resolver =
                DefaultConnectionPolicyResolver(
                    context = RuntimeEnvironment.getApplication(),
                    appSettingsRepository =
                        TestAppSettingsRepository(
                            AppSettingsSerializer.defaultValue
                                .toBuilder()
                                .setNetworkStrategyMemoryEnabled(true)
                                .build(),
                        ),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
                    networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
                    networkEdgePreferenceStore = TestNetworkEdgePreferenceStore(),
                    antiCorrelationRoutingPolicy = antiCorrelationRoutingPolicy(),
                    rememberedNetworkPolicyStore = rememberedStore,
                    rootHelperManager = RootHelperManager(),
                    environmentDetector = EnvironmentDetector(),
                    serverCapabilityStore = TestServerCapabilityStore(),
                    awgEgressSelectionProvider = StaticAwgEgressSelectionProvider(null),
                    destinationRoutingPolicySource = EmptyDestinationRoutingPolicySource,
                    proxySessionSecretResolver = ProxySessionSecretResolver(EmptyWsTunnelWorkerCredentialStore),
                )

            val resolution = resolver.resolve(mode = Mode.VPN)

            assertNull(resolution.matchedNetworkPolicy)
            assertNull(resolution.rememberedPolicyAppliedByExactMatch)
            assertEquals(1, rememberedStore.failures.size)
        }

    @Test
    fun `resolver derives vpn doh primary path from converged direct path dns hints`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val capabilityStore = TestServerCapabilityStore()
            val now = System.currentTimeMillis()
            listOf("Example.org:443", "Video.example.org:443").forEach { authority ->
                capabilityStore.rememberDirectPathObservation(
                    fingerprint = fingerprint,
                    authority = authority,
                    observation =
                        ServerCapabilityObservation(
                            quicUsable = false,
                            udpUsable = false,
                            fallbackRequired = true,
                            transportPolicy =
                                TransportPolicy(
                                    quicMode = QuicMode.SOFT_DISABLE,
                                    preferredStack = PreferredStack.H2,
                                    dnsMode = DnsMode.DOH_PRIMARY,
                                    tcpFamily = TcpFamily.NONE,
                                    outcome = DirectModeOutcome.TRANSPARENT_OK,
                                ),
                            policyConfirmedAt = now,
                            dnsClassification = DirectDnsClassification.POISONED,
                            transportClass = DirectTransportClass.QUIC_BLOCK_SUSPECT,
                            reasonCode = DirectModeReasonCode.QUIC_BLOCKED,
                        ),
                    recordedAt = now,
                )
            }
            val resolver =
                DefaultConnectionPolicyResolver(
                    context = RuntimeEnvironment.getApplication(),
                    appSettingsRepository = TestAppSettingsRepository(plainUdpSettings()),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
                    networkEdgePreferenceStore = TestNetworkEdgePreferenceStore(),
                    antiCorrelationRoutingPolicy = antiCorrelationRoutingPolicy(),
                    rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
                    rootHelperManager = RootHelperManager(),
                    environmentDetector = EnvironmentDetector(),
                    serverCapabilityStore = capabilityStore,
                    awgEgressSelectionProvider = StaticAwgEgressSelectionProvider(null),
                    destinationRoutingPolicySource = EmptyDestinationRoutingPolicySource,
                    proxySessionSecretResolver = ProxySessionSecretResolver(EmptyWsTunnelWorkerCredentialStore),
                )

            val resolution = resolver.resolve(mode = Mode.VPN)

            assertEquals(DnsProviderAdGuard, resolution.activeDns.providerId)
            assertEquals(EncryptedDnsProtocolDoh, resolution.activeDns.encryptedDnsProtocol)
            assertEquals("dns.adguard-dns.com", resolution.activeDns.encryptedDnsHost)
        }

    @Test
    fun `resolver promotes divergent correlated mapping into vpn dns selection`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val capabilityStore = TestServerCapabilityStore()
            val now = System.currentTimeMillis()
            listOf("Example.org:443", "Video.example.org:443").forEach { authority ->
                capabilityStore.rememberDirectPathObservation(
                    fingerprint = fingerprint,
                    authority = authority,
                    observation =
                        ServerCapabilityObservation(
                            transportPolicy =
                                TransportPolicy(
                                    dnsMode = DnsMode.DOH_SECONDARY,
                                ),
                            policyConfirmedAt = now,
                            ipSetDigest = "198.18.0.10",
                            dnsClassification = DirectDnsClassification.DIVERGENT,
                            transportClass = DirectTransportClass.IP_BLOCK_SUSPECT,
                            reasonCode = DirectModeReasonCode.IP_BLOCKED,
                        ),
                    recordedAt = now,
                )
            }
            val resolver =
                DefaultConnectionPolicyResolver(
                    context = RuntimeEnvironment.getApplication(),
                    appSettingsRepository = TestAppSettingsRepository(plainUdpSettings()),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
                    networkEdgePreferenceStore = TestNetworkEdgePreferenceStore(),
                    antiCorrelationRoutingPolicy = antiCorrelationRoutingPolicy(),
                    rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
                    rootHelperManager = RootHelperManager(),
                    environmentDetector = EnvironmentDetector(),
                    serverCapabilityStore = capabilityStore,
                    awgEgressSelectionProvider = StaticAwgEgressSelectionProvider(null),
                    destinationRoutingPolicySource = EmptyDestinationRoutingPolicySource,
                    proxySessionSecretResolver = ProxySessionSecretResolver(EmptyWsTunnelWorkerCredentialStore),
                )

            val resolution = resolver.resolve(mode = Mode.VPN)

            assertEquals(DnsProviderDnsSb, resolution.activeDns.providerId)
            assertEquals(EncryptedDnsProtocolDoh, resolution.activeDns.encryptedDnsProtocol)
            assertEquals("dns.sb", resolution.activeDns.encryptedDnsHost)
        }

    @Test
    fun `resolver ignores ip-only direct path dns hints when deriving vpn path`() =
        runTest {
            val fingerprint = sampleFingerprint()
            val capabilityStore = TestServerCapabilityStore()
            val now = System.currentTimeMillis()
            capabilityStore.rememberDirectPathObservation(
                fingerprint = fingerprint,
                authority = "203.0.113.10:443",
                observation =
                    ServerCapabilityObservation(
                        transportPolicy =
                            TransportPolicy(
                                dnsMode = DnsMode.DOH_PRIMARY,
                            ),
                        policyConfirmedAt = now,
                    ),
                recordedAt = now,
            )
            val resolver =
                DefaultConnectionPolicyResolver(
                    context = RuntimeEnvironment.getApplication(),
                    appSettingsRepository = TestAppSettingsRepository(encryptedGoogleSettings()),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(fingerprint),
                    networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
                    networkEdgePreferenceStore = TestNetworkEdgePreferenceStore(),
                    antiCorrelationRoutingPolicy = antiCorrelationRoutingPolicy(),
                    rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
                    rootHelperManager = RootHelperManager(),
                    environmentDetector = EnvironmentDetector(),
                    serverCapabilityStore = capabilityStore,
                    awgEgressSelectionProvider = StaticAwgEgressSelectionProvider(null),
                    destinationRoutingPolicySource = EmptyDestinationRoutingPolicySource,
                    proxySessionSecretResolver = ProxySessionSecretResolver(EmptyWsTunnelWorkerCredentialStore),
                )

            val resolution = resolver.resolve(mode = Mode.VPN)

            assertEquals(DnsProviderGoogle, resolution.activeDns.providerId)
            assertEquals("dns.google", resolution.activeDns.encryptedDnsHost)
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

    private class FakeRootHelperManager(
        private val startedSocketPath: String,
    ) : RootHelperManager() {
        val syncCalls = mutableListOf<Boolean>()
        private var activePath: String? = null

        override val socketPath: String?
            get() = activePath

        override suspend fun syncRootMode(
            context: Context,
            root: RootSettingsSection,
        ): String? {
            syncCalls += root.rootModeEnabled
            activePath = startedSocketPath.takeIf { root.rootModeEnabled }
            return activePath
        }

        override fun stop() {
            activePath = null
        }
    }
}

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionPolicyAwgResolverTest {
    @Test
    fun `resolver injects selected standalone awg egress into vpn preferences`() =
        runTest {
            val selectedAwg =
                AwgActivationRequest(
                    profileId = "awg-selected",
                    privateKey = "private",
                    peerPublicKey = "peer",
                    endpointHost = "198.51.100.10",
                    endpointPort = 51820,
                    interfaceAddressV4 = "10.8.0.2/32",
                    dnsServers = listOf("10.8.0.1", "10.8.0.3"),
                )
            val resolver =
                DefaultConnectionPolicyResolver(
                    context = RuntimeEnvironment.getApplication(),
                    appSettingsRepository =
                        TestAppSettingsRepository(
                            AppSettingsSerializer.defaultValue
                                .toBuilder()
                                .setEnableCmdSettings(true)
                                .build(),
                        ),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
                    networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
                    networkEdgePreferenceStore = TestNetworkEdgePreferenceStore(),
                    antiCorrelationRoutingPolicy = antiCorrelationRoutingPolicy(),
                    rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
                    rootHelperManager = RootHelperManager(),
                    environmentDetector = EnvironmentDetector(),
                    serverCapabilityStore = TestServerCapabilityStore(),
                    awgEgressSelectionProvider = StaticAwgEgressSelectionProvider(selectedAwg),
                    destinationRoutingPolicySource = EmptyDestinationRoutingPolicySource,
                    proxySessionSecretResolver = ProxySessionSecretResolver(EmptyWsTunnelWorkerCredentialStore),
                )

            val resolution = resolver.resolve(mode = Mode.VPN)

            assertEquals(false, resolution.settings.enableCmdSettings)
            assertEquals("10.8.0.1", resolution.activeDns.dnsIp)
            assertEquals(com.poyka.ripdpi.data.DnsModePlainUdp, resolution.activeDns.mode)
            assertEquals(true, resolution.activeDns.routeThroughProxy)
            assertEquals("awg-selected", resolution.proxyPreferences.awgConfigOrNull()?.profileId)
            assertEquals("198.51.100.10", resolution.proxyPreferences.awgConfigOrNull()?.endpointHost)
        }

    @Test
    fun `remembered vpn policy replay preserves selected awg egress`() =
        runTest {
            val selectedAwg =
                AwgActivationRequest(
                    profileId = "awg-selected",
                    privateKey = "private",
                    peerPublicKey = "peer",
                    endpointHost = "198.51.100.10",
                    endpointPort = 51820,
                    interfaceAddressV4 = "10.8.0.2/32",
                )
            val rememberedStore =
                TestRememberedNetworkPolicyStore().apply {
                    validatedMatch =
                        sampleRememberedPolicyEntity(mode = Mode.VPN).copy(
                            proxyConfigJson =
                                RipDpiProxyUIPreferences(
                                    relay =
                                        RipDpiRelayConfig(
                                            enabled = true,
                                            kind = RelayKindVlessReality,
                                            profileId = "remembered-relay",
                                        ),
                                ).toNativeConfigJson(),
                            connectionConcurrencyPolicyJson =
                                Json.encodeToString(
                                    RememberedConnectionConcurrencyPolicyJson(
                                        selectedProfileId = "firefox_stable",
                                        perProfileCaps = mapOf("firefox_stable" to 4),
                                    ),
                                ),
                        )
                }
            val resolver =
                DefaultConnectionPolicyResolver(
                    context = RuntimeEnvironment.getApplication(),
                    appSettingsRepository =
                        TestAppSettingsRepository(
                            AppSettingsSerializer.defaultValue
                                .toBuilder()
                                .setNetworkStrategyMemoryEnabled(true)
                                .build(),
                        ),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
                    networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
                    networkEdgePreferenceStore = TestNetworkEdgePreferenceStore(),
                    antiCorrelationRoutingPolicy = antiCorrelationRoutingPolicy(),
                    rememberedNetworkPolicyStore = rememberedStore,
                    rootHelperManager = RootHelperManager(),
                    environmentDetector = EnvironmentDetector(),
                    serverCapabilityStore = TestServerCapabilityStore(),
                    awgEgressSelectionProvider = StaticAwgEgressSelectionProvider(selectedAwg),
                    destinationRoutingPolicySource = EmptyDestinationRoutingPolicySource,
                    proxySessionSecretResolver = ProxySessionSecretResolver(EmptyWsTunnelWorkerCredentialStore),
                )

            val resolution = resolver.resolve(mode = Mode.VPN)

            assertEquals(true, resolution.rememberedPolicyAppliedByExactMatch)
            assertEquals("awg-selected", resolution.proxyPreferences.awgConfigOrNull()?.profileId)
            assertEquals("198.51.100.10", resolution.proxyPreferences.awgConfigOrNull()?.endpointHost)
            val concurrencyPolicy =
                decodeRipDpiProxyUiPreferences(resolution.proxyPreferences.toNativeConfigJson())
                    ?.runtimeContext
                    ?.connectionConcurrency
            assertEquals("firefox_stable", concurrencyPolicy?.selectedProfileId)
            assertEquals(4, concurrencyPolicy?.perProfileCaps?.get("firefox_stable"))
        }
}
