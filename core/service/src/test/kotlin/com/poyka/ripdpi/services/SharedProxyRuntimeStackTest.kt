package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.OwnedRelayQuicMigrationConfig
import com.poyka.ripdpi.core.RipDpiProtocolConfig
import com.poyka.ripdpi.core.RipDpiProxyJsonPreferences
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.isUdpAssociateEnabled
import com.poyka.ripdpi.core.relayConfigOrNull
import com.poyka.ripdpi.core.withRelayRuntimeSelection
import com.poyka.ripdpi.data.InitialTransportSelectionException
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedProxyRuntimeStackTest {
    @Test
    fun awgHandshakeWithoutEgressRejectsBeforeProxyStartup() =
        runTest {
            val fixture = createFixture()
            val states = mutableListOf<String>()

            val error =
                runCatching {
                    fixture.stack.start(
                        proxyPreferences = awgPreferences(),
                        onRelayExit = {},
                        onWarpExit = {},
                        onAwgExit = {},
                        onProxyExit = {},
                        initialRelayRacePlan = awgPlan(),
                        onInitialRelayRaceState = { states += it.state },
                    )
                }.exceptionOrNull()

            assertTrue(error is InitialTransportSelectionException)
            assertTrue(fixture.proxyFactory.runtimes.isEmpty())
            assertEquals(1, fixture.awgFactory.lastRuntime.stopCount)
            assertEquals("exhausted", states.last())
        }

    @Test
    fun awgInternetEgressAllowsProxyStartup() =
        runTest {
            var probedEndpoint: LocalProxyEndpoint? = null
            var probedUrl: String? = null
            var probedRequirements: EgressRequirements? = null
            val fixture =
                createFixture(
                    awgActiveProbe =
                        RelayActiveProbe { endpoint, url, requirements ->
                            probedEndpoint = endpoint
                            probedUrl = url
                            probedRequirements = requirements
                            RelayActiveProbeResult(true, statusCode = 204, latencyMs = 12L)
                        },
                )
            val selected = mutableListOf<InitialRelayRaceResult>()

            fixture.stack.start(
                proxyPreferences = awgPreferences(),
                onRelayExit = {},
                onWarpExit = {},
                onAwgExit = {},
                onProxyExit = {},
                initialRelayRacePlan = awgPlan(),
                onInitialRelaySelected = selected::add,
            )

            assertEquals(1, fixture.proxyFactory.runtimes.size)
            assertEquals(0, fixture.awgFactory.lastRuntime.stopCount)
            assertEquals(AwgProfileId, selected.single().selectedCandidate.profileId)
            assertEquals(LocalProxyEndpoint("127.0.0.1", 10_808), probedEndpoint)
            assertEquals("https://probe.example/generate_204", probedUrl)
            assertEquals(EgressRequirements(tcpConnect = true, udpAssociate = false), probedRequirements)
            fixture.stack.stop(skipRuntimeShutdown = false)
        }

    @Test
    fun rememberedJsonUsesPromotedRelayEndpointBeforeProxyStartup() =
        runTest {
            val fixture = createFixture()
            val selected = mutableListOf<InitialRelayRaceResult>()

            fixture.stack.start(
                proxyPreferences = rememberedJsonPreferences(),
                onRelayExit = {},
                onWarpExit = {},
                onAwgExit = {},
                onProxyExit = {},
                initialRelayRacePlan = racePlan(),
                onInitialRelaySelected = selected::add,
            )

            val renderedRelay =
                fixture.proxyFactory.lastRuntime.lastPreferences
                    ?.relayConfigOrNull()
            assertEquals(RelayKindHysteria2, renderedRelay?.kind)
            assertEquals(HysteriaProfileId, renderedRelay?.profileId)
            assertEquals(true, renderedRelay?.udpEnabled)
            assertEquals("127.0.0.1", renderedRelay?.localSocksHost)
            assertEquals(HysteriaRacePort, renderedRelay?.localSocksPort)
            assertEquals(HysteriaProfileId, selected.single().selectedCandidate.profileId)
            fixture.stack.stop(skipRuntimeShutdown = false)
        }

    @Test
    fun udpPreflightPreservesHysteriaCapabilityInPromotedProxyConfig() =
        runTest {
            val fixture = createFixture()
            val preferences =
                RipDpiProxyUIPreferences(
                    protocols = RipDpiProtocolConfig(udpAssociateEnabled = true),
                    relay =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindVlessReality,
                            profileId = RealityProfileId,
                        ),
                )
            val plan =
                InitialRelayRacePlan(
                    probePlan =
                        testProbePlan(
                            "https://probe.example/udp",
                            EgressRequirements(tcpConnect = true, udpAssociate = true),
                        ),
                    candidates =
                        listOf(
                            InitialRelayCandidate(
                                InitialRelayTransportClass.UdpObfuscation,
                                HysteriaProfileId,
                                RelayKindHysteria2,
                            ),
                        ),
                    requirements = EgressRequirements(tcpConnect = true, udpAssociate = true),
                    healthScope = TestHealthScope,
                )

            fixture.stack.start(
                proxyPreferences = preferences,
                onRelayExit = {},
                onWarpExit = {},
                onAwgExit = {},
                onProxyExit = {},
                initialRelayRacePlan = plan,
            )

            val renderedRelay =
                fixture.proxyFactory.lastRuntime.lastPreferences
                    ?.relayConfigOrNull()
            assertEquals(RelayKindHysteria2, renderedRelay?.kind)
            assertEquals(true, renderedRelay?.udpEnabled)
            fixture.stack.stop(skipRuntimeShutdown = false)
        }

    @Test
    fun `tcp-only race plan disables UDP associate for the relay session`() =
        runTest {
            var probedRequirements: EgressRequirements? = null
            val fixture =
                createFixture(
                    relayActiveProbe =
                        RelayActiveProbe { _, _, requirements ->
                            probedRequirements = requirements
                            RelayActiveProbeResult(true, statusCode = 204, latencyMs = 10L)
                        },
                )
            val preferences =
                RipDpiProxyJsonPreferences(
                    configJson =
                        RipDpiProxyUIPreferences(
                            protocols = RipDpiProtocolConfig(udpAssociateEnabled = true),
                            relay =
                                RipDpiRelayConfig(
                                    enabled = true,
                                    kind = RelayKindVlessReality,
                                    profileId = RealityProfileId,
                                ),
                        ).toNativeConfigJson(),
                )
            val effectiveRequirements = EgressRequirements(tcpConnect = true, udpAssociate = false)
            val plan =
                InitialRelayRacePlan(
                    probePlan = testProbePlan("https://probe.example/generate_204", effectiveRequirements),
                    candidates =
                        listOf(
                            InitialRelayCandidate(
                                InitialRelayTransportClass.TlsMimicry,
                                RealityProfileId,
                                RelayKindVlessReality,
                            ),
                        ),
                    requirements = effectiveRequirements,
                    healthScope = TestHealthScope,
                )

            fixture.stack.start(
                proxyPreferences = preferences,
                onRelayExit = {},
                onWarpExit = {},
                onAwgExit = {},
                onProxyExit = {},
                initialRelayRacePlan = plan,
            )

            assertEquals(effectiveRequirements, probedRequirements)
            assertEquals(
                false,
                requireNotNull(fixture.proxyFactory.lastRuntime.lastPreferences).isUdpAssociateEnabled(),
            )
            fixture.stack.stop(skipRuntimeShutdown = false)
        }

    @Test
    fun mismatchedPromotedRelayEndpointRejectsBeforeSelectionOrProxyStartup() =
        runTest {
            val fixture = createFixture { preferences, _, _, _ -> preferences }
            val selected = mutableListOf<InitialRelayRaceResult>()

            val result =
                runCatching {
                    fixture.stack.start(
                        proxyPreferences = rememberedJsonPreferences(),
                        onRelayExit = {},
                        onWarpExit = {},
                        onAwgExit = {},
                        onProxyExit = {},
                        initialRelayRacePlan = racePlan(),
                        onInitialRelaySelected = selected::add,
                    )
                }

            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertTrue(selected.isEmpty())
            assertTrue(fixture.proxyFactory.runtimes.isEmpty())
            fixture.stack.stop(skipRuntimeShutdown = false)
        }

    @Test
    fun singleFailedRelayPreflightRejectsBeforeProxyStartup() =
        runTest {
            val fixture = createFixture()
            val plan =
                InitialRelayRacePlan(
                    probePlan =
                        testProbePlan(
                            "https://probe.example/generate_204",
                            EgressRequirements(tcpConnect = true, udpAssociate = false),
                        ),
                    candidates =
                        listOf(
                            InitialRelayCandidate(
                                InitialRelayTransportClass.TlsMimicry,
                                RealityProfileId,
                                RelayKindVlessReality,
                            ),
                        ),
                    requirements = EgressRequirements(tcpConnect = true, udpAssociate = false),
                    healthScope = TestHealthScope,
                )

            val error =
                runCatching {
                    fixture.stack.start(
                        proxyPreferences = rememberedJsonPreferences(),
                        onRelayExit = {},
                        onWarpExit = {},
                        onAwgExit = {},
                        onProxyExit = {},
                        initialRelayRacePlan = plan,
                    )
                }.exceptionOrNull()

            assertTrue(error is InitialTransportSelectionException)
            assertEquals(1, fixture.relayFactory.lastRuntime.stopCount)
            assertTrue(fixture.proxyFactory.runtimes.isEmpty())
        }

    @Test
    fun udpRequirementRejectsTcpOnlyRelayBeforeRelayAndProxyStartup() =
        runTest {
            val fixture = createFixture()
            val preferences =
                RipDpiProxyUIPreferences(
                    relay =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindVlessReality,
                            profileId = RealityProfileId,
                        ),
                )

            val error =
                runCatching {
                    fixture.stack.start(
                        proxyPreferences = preferences,
                        onRelayExit = {},
                        onWarpExit = {},
                        onAwgExit = {},
                        onProxyExit = {},
                    )
                }.exceptionOrNull()

            assertTrue(error is InitialTransportSelectionException)
            assertTrue(fixture.relayFactory.runtimes.isEmpty())
            assertTrue(fixture.proxyFactory.runtimes.isEmpty())
        }

    @Test
    fun stopAttemptsRelayCleanupAfterProxyStopFailure() =
        runTest {
            val fixture = createFixture()
            fixture.stack.start(
                proxyPreferences = rememberedJsonPreferences(),
                onRelayExit = {},
                onWarpExit = {},
                onAwgExit = {},
                onProxyExit = {},
                initialRelayRacePlan = racePlan(),
            )
            fixture.proxyFactory.lastRuntime.stopFailure = IllegalStateException("proxy stop failed")

            val failure = runCatching { fixture.stack.stop(skipRuntimeShutdown = false) }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(1, fixture.proxyFactory.lastRuntime.stopCount)
            assertEquals(1, fixture.relayFactory.lastRuntime.stopCount)
        }

    private fun TestScope.createFixture(
        awgActiveProbe: RelayActiveProbe =
            RelayActiveProbe { _, _, _ ->
                RelayActiveProbeResult(false, latencyMs = 10L, failure = "io_error")
            },
        relayActiveProbe: RelayActiveProbe =
            RelayActiveProbe { endpoint, probeUrl, _ ->
                if (probeUrl.endsWith("/udp") || endpoint.port == HysteriaRacePort) {
                    RelayActiveProbeResult(true, statusCode = 204, latencyMs = 10L)
                } else {
                    RelayActiveProbeResult(false, latencyMs = 10L, failure = "io_error")
                }
            },
        renderer: (RipDpiProxyPreferences, RipDpiRelayConfig, String, Int) -> RipDpiProxyPreferences =
            { preferences, selection, host, port ->
                preferences.withRelayRuntimeSelection(selection, host, port)
            },
    ): Fixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var port = RealityRacePort
        val relayFactory =
            TestRipDpiRelayFactory {
                TestRelayRuntime().apply {
                    telemetry =
                        NativeRuntimeSnapshot(
                            source = "relay",
                            state = "running",
                            health = "healthy",
                            listenerAddress = "127.0.0.1:$port",
                        )
                    port = HysteriaRacePort
                }
            }
        val upstreamRelaySupervisor =
            UpstreamRelaySupervisor(
                scope = backgroundScope,
                dispatcher = dispatcher,
                relayFactory = relayFactory,
                naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                runtimeConfigResolver =
                    object : UpstreamRelayRuntimeConfigResolver {
                        override suspend fun resolve(
                            config: RipDpiRelayConfig,
                            quicMigrationConfig: OwnedRelayQuicMigrationConfig,
                        ) = sampleResolvedRelayConfig(config.kind, config.profileId).copy(
                            udpEnabled = config.kind == RelayKindHysteria2,
                        )
                    },
                initialRelayRaceRunnerFactory = InitialRelayRaceRunnerFactory(relayActiveProbe),
            )
        val proxyFactory = TestRipDpiProxyFactory()
        val awgFactory = TestRipDpiAmneziaWgFactory()
        val awgSupervisor =
            AmneziaWgRuntimeSupervisor(
                scope = backgroundScope,
                dispatcher = dispatcher,
                amneziaWgFactory = awgFactory,
                runtimeConfigResolver = RecordingAmneziaWgRuntimeConfigResolver(),
            )
        val stack =
            SharedProxyRuntimeStack(
                upstreamRelaySupervisor = upstreamRelaySupervisor,
                warpRuntimeSupervisor =
                    WarpRuntimeSupervisor(
                        scope = backgroundScope,
                        dispatcher = dispatcher,
                        warpFactory = TestRipDpiWarpFactory(),
                        runtimeConfigResolver = TestWarpRuntimeConfigResolver(),
                    ),
                amneziaWgRuntimeSupervisor = awgSupervisor,
                proxyRuntimeSupervisor =
                    ProxyRuntimeSupervisor(
                        scope = backgroundScope,
                        dispatcher = dispatcher,
                        ripDpiProxyFactory = proxyFactory,
                        networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                    ),
                awgEgressReadinessVerifier = AwgEgressReadinessVerifier(awgSupervisor, awgActiveProbe),
                relayRuntimeSelectionRenderer = renderer,
            )
        return Fixture(stack, proxyFactory, relayFactory, awgFactory)
    }

    private fun rememberedJsonPreferences(): RipDpiProxyJsonPreferences =
        RipDpiProxyJsonPreferences(
            configJson =
                RipDpiProxyUIPreferences(
                    protocols = RipDpiProtocolConfig(udpAssociateEnabled = false),
                    relay =
                        RipDpiRelayConfig(
                            enabled = true,
                            kind = RelayKindVlessReality,
                            profileId = RealityProfileId,
                            localSocksHost = "127.0.0.1",
                            localSocksPort = RememberedRelayPort,
                        ),
                ).toNativeConfigJson(),
            localListenPortOverride = 0,
            localAuthToken = TestLocalProxyAuth,
        )

    private fun awgPreferences(): RipDpiProxyUIPreferences =
        RipDpiProxyUIPreferences(
            protocols = RipDpiProtocolConfig(udpAssociateEnabled = false),
            awg =
                AwgActivationRequest(
                    profileId = AwgProfileId,
                    privateKey = "privkey==",
                    peerPublicKey = "peerpub==",
                    endpointHost = "vpn.example.org",
                    endpointPort = 51820,
                    interfaceAddressV4 = "10.8.0.2/32",
                ),
        )

    private fun awgPlan(): InitialRelayRacePlan =
        InitialRelayRacePlan(
            probePlan =
                testProbePlan(
                    "https://probe.example/generate_204",
                    EgressRequirements(tcpConnect = true, udpAssociate = false),
                ),
            candidates =
                listOf(
                    InitialRelayCandidate(
                        InitialRelayTransportClass.UdpObfuscation,
                        AwgProfileId,
                        AmneziaWgEgressKind,
                    ),
                ),
            requirements = EgressRequirements(tcpConnect = true, udpAssociate = false),
            healthScope = TestHealthScope,
        )

    private fun racePlan(): InitialRelayRacePlan =
        InitialRelayRacePlan(
            probePlan =
                testProbePlan(
                    "https://probe.example/generate_204",
                    EgressRequirements(tcpConnect = true, udpAssociate = false),
                ),
            candidates =
                listOf(
                    InitialRelayCandidate(
                        InitialRelayTransportClass.TlsMimicry,
                        RealityProfileId,
                        RelayKindVlessReality,
                    ),
                    InitialRelayCandidate(
                        InitialRelayTransportClass.UdpObfuscation,
                        HysteriaProfileId,
                        RelayKindHysteria2,
                    ),
                ),
            requirements = EgressRequirements(tcpConnect = true, udpAssociate = false),
            healthScope = TestHealthScope,
        )

    private fun testProbePlan(
        targetUrl: String,
        requirements: EgressRequirements,
    ): RelayProbePlan =
        RelayProbePlan(
            targetUrl = targetUrl,
            targetCategory = RelayTargetCategory.ApplicationHttp,
            requirements = requirements,
        )

    private data class Fixture(
        val stack: SharedProxyRuntimeStack,
        val proxyFactory: TestRipDpiProxyFactory,
        val relayFactory: TestRipDpiRelayFactory,
        val awgFactory: TestRipDpiAmneziaWgFactory,
    )

    private companion object {
        const val RealityProfileId = "remembered-reality"
        const val HysteriaProfileId = "race-hysteria"
        const val RememberedRelayPort = 11_980
        const val RealityRacePort = 19_001
        const val HysteriaRacePort = 19_002
        val TestHealthScope = RelayHealthScope(persistentNetworkHash = "network-test", sessionGeneration = 1L)
        const val TestLocalProxyAuth = "alpha-123"
        const val AwgProfileId = "simple-seed-awg"
    }
}
