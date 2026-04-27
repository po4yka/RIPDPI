package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderGoogle
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.WarpRouteModeRules
import com.poyka.ripdpi.data.activeDnsSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("detekt.LargeClass")
class VpnServiceRuntimeCoordinatorTest {
    private data class Env(
        val coordinator: VpnServiceRuntimeCoordinator,
        val store: TestServiceStateStore,
        val host: TestVpnServiceHost,
        val factory: TestRipDpiProxyFactory,
        val warpFactory: TestRipDpiWarpFactory,
        val bridgeFactory: TestTun2SocksBridgeFactory,
        val tunnelProvider: TestVpnTunnelSessionProvider,
        val runtimeRegistry: ServiceRuntimeRegistry,
        val resolver: TestConnectionPolicyResolver,
        val handoverMonitor: TestNetworkHandoverMonitor,
        val handoverEvents: TestPolicyHandoverEventStore,
        val permissionWatchdog: TestPermissionWatchdog,
        val vpnProtectFailureMonitor: TestVpnProtectFailureMonitor,
        val resolverOverrides: TestResolverOverrideStore,
        val preferredPaths: TestNetworkDnsPathPreferenceStore,
        val events: MutableList<String>,
    )

    @Test
    fun successfulStartRunsProxyBeforeTunnel() =
        runTest {
            val env = newEnv()

            env.coordinator.start()
            runCurrent()

            assertEquals(AppStatus.Running to Mode.VPN, env.store.status.value)
            assertNotNull(env.runtimeRegistry.current(Mode.VPN))
            assertEquals(listOf("proxy:start", "vpn:establish", "tunnel:start"), env.events.take(3))
            assertEquals(1, env.host.underlyingNetworkSyncs)
        }

    @Test
    fun successfulStartRunsWarpBeforeProxyBeforeTunnelWhenWarpRoutingEnabled() =
        runTest {
            val env =
                newEnv(
                    resolutions =
                        listOf(
                            sampleResolution(
                                mode = Mode.VPN,
                                proxyPreferences =
                                    RipDpiProxyUIPreferences(
                                        warp =
                                            RipDpiWarpConfig(
                                                enabled = true,
                                                routeMode = WarpRouteModeRules,
                                                routeHosts = "example.com",
                                            ),
                                    ),
                            ),
                        ),
                )

            env.coordinator.start()
            runCurrent()

            assertEquals(listOf("warp:start", "proxy:start", "vpn:establish", "tunnel:start"), env.events.take(4))
            assertEquals(1, env.warpFactory.runtimes.size)
        }

    @Test
    fun stopStopsTunnelBeforeProxy() =
        runTest {
            val env = newEnv()

            env.coordinator.start()
            runCurrent()
            env.coordinator.stop()
            runCurrent()

            assertEquals(AppStatus.Halted to Mode.VPN, env.store.status.value)
            assertTrue(env.events.containsAll(listOf("tunnel:stop", "vpn:session-close", "proxy:stop")))
            assertEquals("tunnel:stop", env.events[3])
        }

    @Test
    fun tunnelStartFailureEmitsFailureAndStopsProxy() =
        runTest {
            val env = newEnv().also { it.bridgeFactory.bridge.startFailure = IllegalStateException("boom") }

            env.coordinator.start()
            runCurrent()

            assertEquals(AppStatus.Halted to Mode.VPN, env.store.status.value)
            assertTrue(env.store.eventHistory.single() is ServiceEvent.Failed)
            assertEquals(1, env.factory.lastRuntime.stopCount)
            assertTrue(env.tunnelProvider.session.closed)
        }

    @Test
    fun stopClearsRuntimeAndTunnelSession() =
        runTest {
            val env = newEnv()

            env.coordinator.start()
            runCurrent()
            env.coordinator.stop()
            runCurrent()

            assertEquals(AppStatus.Halted to Mode.VPN, env.store.status.value)
            assertNull(env.runtimeRegistry.current(Mode.VPN))
            assertTrue(env.tunnelProvider.session.closed)
        }

    @Test
    fun resolverRefreshRestartsOnlyTunnelWhenDnsSignatureChanges() =
        runTest {
            val env = newEnv()

            env.coordinator.start()
            runCurrent()

            val updatedSettings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setDnsMode(DnsModePlainUdp)
                    .setDnsIp("8.8.8.8")
                    .build()
            env.resolver.enqueue(
                sampleResolution(
                    mode = Mode.VPN,
                    settings = updatedSettings,
                    activeDns = updatedSettings.activeDnsSettings(),
                ),
            )

            advanceTimeBy(1_000L)
            repeat(3) { runCurrent() }

            assertEquals(1, env.factory.runtimes.size)
            assertEquals(1, env.bridgeFactory.bridge.stopCount)
            assertEquals("8.8.8.8", env.tunnelProvider.lastDns)
        }

    @Test
    fun vpnTunnelUsesTelemetryResolvedProxyEndpointInsteadOfPersistedProxyPort() =
        runTest {
            val settings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setProxyPort(4545)
                    .build()
            val env =
                newEnv(
                    resolutions = listOf(sampleResolution(mode = Mode.VPN, settings = settings)),
                    runtimeFactory = { events ->
                        TestProxyRuntime(events).apply {
                            telemetry = telemetry.copy(listenerAddress = "127.0.0.1:18443")
                        }
                    },
                )

            env.coordinator.start()
            runCurrent()

            assertEquals(
                18443,
                env.bridgeFactory.bridge.startedConfig
                    ?.socks5Port,
            )
            assertEquals(
                "127.0.0.1",
                env.bridgeFactory.bridge.startedConfig
                    ?.socks5Address,
            )
            assertNotEquals(
                4545,
                env.bridgeFactory.bridge.startedConfig
                    ?.socks5Port,
            )
        }

    @Test
    fun handoverRestartRotatesProxyCredentialsAndEndpoint() =
        runTest {
            val initialFingerprint = sampleFingerprint()
            val newFingerprint = sampleFingerprint(dnsServers = listOf("8.8.4.4"))
            var runtimeCount = 0
            val env =
                newEnv(
                    fingerprint = initialFingerprint,
                    resolutions =
                        listOf(
                            sampleResolution(mode = Mode.VPN, policySignature = "initial"),
                            sampleResolution(mode = Mode.VPN, policySignature = "handover"),
                        ),
                    runtimeFactory = { events ->
                        runtimeCount += 1
                        TestProxyRuntime(events).apply {
                            telemetry = telemetry.copy(listenerAddress = "127.0.0.1:${18080 + runtimeCount}")
                        }
                    },
                )

            env.coordinator.start()
            runCurrent()

            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = initialFingerprint,
                    currentFingerprint = newFingerprint,
                    classification = "transport_switch",
                    occurredAt = 2_000L,
                ),
            )
            repeat(3) { runCurrent() }

            assertEquals(2, env.factory.runtimes.size)
            assertEquals(2, env.bridgeFactory.bridge.startedConfigs.size)
            assertNotEquals(
                env.factory.runtimes[0]
                    .lastPreferences
                    ?.localAuthToken,
                env.factory.runtimes[1]
                    .lastPreferences
                    ?.localAuthToken,
            )
            assertNotEquals(
                env.bridgeFactory.bridge.startedConfigs[0]
                    .password,
                env.bridgeFactory.bridge.startedConfigs[1]
                    .password,
            )
            assertNotEquals(
                env.bridgeFactory.bridge.startedConfigs[0]
                    .socks5Port,
                env.bridgeFactory.bridge.startedConfigs[1]
                    .socks5Port,
            )
            assertEquals(2, env.host.underlyingNetworkSyncs)
        }

    @Test
    fun dnsRefreshReusesCurrentProxyEndpointAndCredentials() =
        runTest {
            val env =
                newEnv(
                    runtimeFactory = { events ->
                        TestProxyRuntime(events).apply {
                            telemetry = telemetry.copy(listenerAddress = "127.0.0.1:19090")
                        }
                    },
                )

            env.coordinator.start()
            runCurrent()

            val initialTunnelConfig = requireNotNull(env.bridgeFactory.bridge.startedConfig)
            val updatedSettings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setDnsMode(DnsModePlainUdp)
                    .setDnsIp("8.8.8.8")
                    .build()
            env.resolver.enqueue(
                sampleResolution(
                    mode = Mode.VPN,
                    settings = updatedSettings,
                    activeDns = updatedSettings.activeDnsSettings(),
                ),
            )

            advanceTimeBy(1_000L)
            repeat(3) { runCurrent() }

            val refreshedTunnelConfig =
                env.bridgeFactory.bridge.startedConfigs
                    .last()
            assertEquals(1, env.factory.runtimes.size)
            assertEquals(initialTunnelConfig.socks5Port, refreshedTunnelConfig.socks5Port)
            assertEquals(initialTunnelConfig.password, refreshedTunnelConfig.password)
        }

    @Test
    fun preferredEncryptedDnsPathDoesNotTriggerTunnelRebuildLoopOrFallbackWarning() =
        runTest {
            val persistedSettings = AppSettingsSerializer.defaultValue
            val preferredSettings =
                persistedSettings
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
            val env =
                newEnv(
                    resolutions =
                        listOf(
                            sampleResolution(
                                mode = Mode.VPN,
                                settings = persistedSettings,
                                activeDns = preferredSettings.activeDnsSettings(),
                                networkScopeKey = sampleFingerprint().scopeKey(),
                            ),
                        ),
                )

            env.coordinator.start()
            runCurrent()

            val initialConfig = env.bridgeFactory.bridge.startedConfig
            assertEquals("dns.google", initialConfig?.encryptedDnsHost)
            assertEquals(false, initialConfig?.resolverFallbackActive)
            assertEquals(null, initialConfig?.resolverFallbackReason)

            advanceTimeBy(3_000L)
            repeat(3) { runCurrent() }

            assertEquals(1, env.factory.runtimes.size)
            assertEquals(0, env.bridgeFactory.bridge.stopCount)
            assertEquals("198.18.0.53", env.tunnelProvider.lastDns)
        }

    @Test
    fun tunnelTelemetryFailureTransitionsToFailedThenStopped() =
        runTest {
            val env = newEnv(resolutions = listOf(plainDnsResolution()))

            env.coordinator.start()
            runCurrent()
            env.bridgeFactory.bridge.telemetryFailure = IOException("telemetry boom")

            advanceTimeBy(1_000L)
            repeat(3) { runCurrent() }

            assertEquals(AppStatus.Halted to Mode.VPN, env.store.status.value)
            assertTrue(env.store.eventHistory.any { it is ServiceEvent.Failed })
            assertTrue(env.tunnelProvider.session.closed)
        }

    @Test
    fun dnsFailoverRestartsOnlyTunnelAndKeepsProxyRuntimeAlive() =
        runTest {
            val env = newEnv()

            env.coordinator.start()
            runCurrent()
            env.bridgeFactory.bridge.telemetry =
                NativeRuntimeSnapshot(
                    source = "tunnel",
                    state = "running",
                    health = "healthy",
                    dnsQueriesTotal = 1,
                    dnsFailuresTotal = 1,
                    lastDnsError = "resolver timeout",
                )

            advanceTimeBy(1_000L)
            repeat(3) { runCurrent() }

            env.bridgeFactory.bridge.telemetry =
                NativeRuntimeSnapshot(
                    source = "tunnel",
                    state = "running",
                    health = "healthy",
                    dnsQueriesTotal = 2,
                    dnsFailuresTotal = 2,
                    lastDnsError = "resolver timeout",
                )

            advanceTimeBy(1_000L)
            repeat(3) { runCurrent() }

            val override = env.resolverOverrides.override.value
            assertEquals(1, env.factory.runtimes.size)
            assertEquals(1, env.bridgeFactory.bridge.stopCount)
            assertNotNull(override)
            assertEquals("vpn_encrypted_dns_auto_failover: resolver timeout", override?.reason)
            assertEquals(
                override?.resolverId,
                env.bridgeFactory.bridge.startedConfig
                    ?.encryptedDnsResolverId,
            )
            assertEquals(
                override?.protocol,
                env.bridgeFactory.bridge.startedConfig
                    ?.encryptedDnsProtocol,
            )
        }

    @Suppress("LongMethod")
    @Test
    fun dnsFailoverSuccessPersistsWinningPathWithoutRestartingProxy() =
        runTest {
            val scopeKey = sampleFingerprint().scopeKey()
            val env =
                newEnv(
                    resolutions =
                        listOf(
                            sampleResolution(
                                mode = Mode.VPN,
                                networkScopeKey = scopeKey,
                            ),
                        ),
                )

            env.coordinator.start()
            runCurrent()

            env.bridgeFactory.bridge.telemetry =
                NativeRuntimeSnapshot(
                    source = "tunnel",
                    state = "running",
                    health = "healthy",
                    dnsQueriesTotal = 1,
                    dnsFailuresTotal = 1,
                    lastDnsError = "resolver timeout",
                )

            advanceTimeBy(1_000L)
            repeat(3) { runCurrent() }

            env.bridgeFactory.bridge.telemetry =
                NativeRuntimeSnapshot(
                    source = "tunnel",
                    state = "running",
                    health = "healthy",
                    dnsQueriesTotal = 2,
                    dnsFailuresTotal = 2,
                    lastDnsError = "resolver timeout",
                )

            advanceTimeBy(1_000L)
            repeat(3) { runCurrent() }

            val override = requireNotNull(env.resolverOverrides.override.value)
            assertEquals(1, env.factory.runtimes.size)
            assertEquals(
                true,
                env.bridgeFactory.bridge.startedConfig
                    ?.resolverFallbackActive,
            )
            assertEquals(
                override.reason,
                env.bridgeFactory.bridge.startedConfig
                    ?.resolverFallbackReason,
            )

            env.bridgeFactory.bridge.telemetry =
                NativeRuntimeSnapshot(
                    source = "tunnel",
                    state = "running",
                    health = "healthy",
                    dnsQueriesTotal = 1,
                    dnsFailuresTotal = 0,
                    lastDnsError = null,
                )
            advanceTimeBy(1_000L)
            repeat(3) { runCurrent() }

            env.bridgeFactory.bridge.telemetry =
                NativeRuntimeSnapshot(
                    source = "tunnel",
                    state = "running",
                    health = "healthy",
                    dnsQueriesTotal = 2,
                    dnsFailuresTotal = 0,
                    lastDnsError = null,
                )
            advanceTimeBy(1_000L)
            repeat(3) { runCurrent() }

            assertEquals(1, env.factory.runtimes.size)
            assertEquals(1, env.bridgeFactory.bridge.stopCount)
            assertEquals(
                env.bridgeFactory.bridge.startedConfig
                    ?.encryptedDnsHost,
                env.preferredPaths.getPreferredPath(scopeKey)?.host,
            )
            assertEquals(
                env.bridgeFactory.bridge.startedConfig
                    ?.encryptedDnsDohUrl,
                env.preferredPaths.getPreferredPath(scopeKey)?.dohUrl,
            )
        }

    @Test
    fun handoverRestartPublishesPolicyEvent() =
        runTest {
            val initialFingerprint = sampleFingerprint()
            val newFingerprint =
                sampleFingerprint(dnsServers = listOf("8.8.4.4")).copy(
                    networkValidated = false,
                    captivePortalDetected = false,
                )
            val env =
                newEnv(
                    fingerprint = initialFingerprint,
                    resolutions =
                        listOf(
                            sampleResolution(mode = Mode.VPN, policySignature = "initial"),
                            sampleResolution(mode = Mode.VPN, policySignature = "handover"),
                        ),
                )

            env.coordinator.start()
            runCurrent()

            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = initialFingerprint,
                    currentFingerprint = newFingerprint,
                    classification = "transport_switch",
                    occurredAt = 2_000L,
                ),
            )
            repeat(3) { runCurrent() }

            assertEquals(2, env.factory.runtimes.size)
            assertEquals(
                "handover",
                env.handoverEvents.published
                    .single()
                    .policySignature,
            )
            assertEquals(
                false,
                env.handoverEvents.published
                    .single()
                    .currentNetworkValidated,
            )
            assertEquals(
                false,
                env.handoverEvents.published
                    .single()
                    .currentCaptivePortalDetected,
            )
        }

    @Test
    fun handoverRestartUpdatesExplicitHandoverState() =
        runTest {
            val initialFingerprint = sampleFingerprint()
            val newFingerprint = sampleFingerprint(dnsServers = listOf("8.8.4.4"))
            val env =
                newEnv(
                    fingerprint = initialFingerprint,
                    resolutions =
                        listOf(
                            sampleResolution(mode = Mode.VPN, policySignature = "initial"),
                            sampleResolution(mode = Mode.VPN, policySignature = "handover"),
                        ),
                )

            env.coordinator.start()
            runCurrent()

            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = initialFingerprint,
                    currentFingerprint = newFingerprint,
                    classification = "transport_switch",
                    occurredAt = 2_000L,
                ),
            )
            repeat(3) { runCurrent() }
            advanceTimeBy(1_000L)
            repeat(3) { runCurrent() }

            assertEquals(
                com.poyka.ripdpi.data.NetworkHandoverStates.Revalidated,
                env.store.telemetry.value.networkHandoverState,
            )
            assertEquals("transport_switch", env.store.telemetry.value.tunnelTelemetry.networkHandoverClass)
        }

    @Test
    fun tunnelStoppedUnexpectedlyTransitionsToFailed() =
        runTest {
            val env = newEnv(resolutions = listOf(plainDnsResolution()))

            env.coordinator.start()
            runCurrent()

            env.bridgeFactory.bridge.telemetry =
                NativeRuntimeSnapshot(
                    source = "tunnel",
                    state = "idle",
                    health = "healthy",
                    lastError = "tunnel process exited",
                )

            advanceTimeBy(1_000L)
            repeat(3) { runCurrent() }

            assertEquals(AppStatus.Halted to Mode.VPN, env.store.status.value)
            assertTrue(env.store.eventHistory.any { it is ServiceEvent.Failed })
        }

    @Test
    fun vpnTunnelEstablishFailureHaltsAndClosesProxy() =
        runTest {
            val env =
                newEnv().also {
                    it.tunnelProvider.establishFailure = IllegalStateException("VPN permission denied")
                }

            env.coordinator.start()
            runCurrent()

            assertEquals(AppStatus.Halted to Mode.VPN, env.store.status.value)
            assertTrue(env.store.eventHistory.single() is ServiceEvent.Failed)
            assertEquals(1, env.factory.lastRuntime.stopCount)
        }

    @Test
    fun vpnConsentRevocationTransitionsToPermissionFailureAndStops() =
        runTest {
            val env = newEnv()

            env.coordinator.start()
            runCurrent()

            env.permissionWatchdog.emit(
                PermissionChangeEvent(
                    kind = PermissionChangeEvent.KIND_VPN_CONSENT,
                    detectedAt = 2_000L,
                ),
            )
            repeat(3) { runCurrent() }

            assertEquals(AppStatus.Halted to Mode.VPN, env.store.status.value)
            val failure = env.store.eventHistory.last() as ServiceEvent.Failed
            assertEquals(FailureReason.PermissionLost("VPN"), failure.reason)
            assertNull(env.runtimeRegistry.current(Mode.VPN))
        }

    @Test
    fun vpnProtectFailureTransitionsToFailedAndStops() =
        runTest {
            val env = newEnv()

            env.coordinator.start()
            runCurrent()

            env.vpnProtectFailureMonitor.report(
                VpnProtectFailureEvent(
                    fd = 42,
                    reason = FailureReason.PermissionLost("VPN"),
                    detail = "VpnService.protect() returned false",
                    detectedAt = 2_000L,
                ),
            )
            repeat(3) { runCurrent() }

            assertEquals(AppStatus.Halted to Mode.VPN, env.store.status.value)
            val failure = env.store.eventHistory.last() as ServiceEvent.Failed
            assertEquals(FailureReason.PermissionLost("VPN"), failure.reason)
            assertNull(env.runtimeRegistry.current(Mode.VPN))
        }

    @Test
    fun handoverFailureInVpnModeHalts() =
        runTest {
            val initialFingerprint = sampleFingerprint()
            val newFingerprint = sampleFingerprint(dnsServers = listOf("8.8.4.4"))
            var callCount = 0
            val env =
                newEnv(
                    fingerprint = initialFingerprint,
                    resolutions =
                        listOf(
                            sampleResolution(mode = Mode.VPN, policySignature = "initial"),
                            sampleResolution(mode = Mode.VPN, policySignature = "handover"),
                        ),
                    runtimeFactory = { events ->
                        callCount += 1
                        if (callCount > 1) {
                            TestProxyRuntime(events).apply { startFailure = IOException("restart boom") }
                        } else {
                            TestProxyRuntime(events)
                        }
                    },
                )

            env.coordinator.start()
            runCurrent()

            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = initialFingerprint,
                    currentFingerprint = newFingerprint,
                    classification = "transport_switch",
                    occurredAt = 2_000L,
                ),
            )
            // Exhaust exponential backoff retries: 2s + 4s + 8s + 16s = 30s
            advanceTimeBy(31_000L)
            repeat(5) { runCurrent() }

            assertEquals(AppStatus.Halted to Mode.VPN, env.store.status.value)
            assertTrue(env.store.eventHistory.any { it is ServiceEvent.Failed })
        }

    @Test
    fun staleSupersededProxyExitDoesNotHaltRebuiltVpnSession() =
        runTest {
            val env = buildStaleProxyExitEnv()

            env.coordinator.start()
            runCurrent()

            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = env.initialFingerprint,
                    currentFingerprint = env.newFingerprint,
                    classification = "transport_switch",
                    occurredAt = 2_000L,
                ),
            )
            advanceTimeBy(5_000L)
            repeat(4) { runCurrent() }

            env.oldRuntime.complete(23)
            repeat(3) { runCurrent() }

            assertEquals(AppStatus.Running to Mode.VPN, env.store.status.value)
            assertTrue(env.store.eventHistory.none { it is ServiceEvent.Failed })
            assertNotNull(env.runtimeRegistry.current(Mode.VPN))
        }

    private data class StaleProxyExitEnv(
        val coordinator: VpnServiceRuntimeCoordinator,
        val store: TestServiceStateStore,
        val handoverMonitor: TestNetworkHandoverMonitor,
        val runtimeRegistry: DefaultServiceRuntimeRegistry,
        val oldRuntime: DelayedStopVpnProxyRuntime,
        val newFingerprint: com.poyka.ripdpi.data.NetworkFingerprint,
        val initialFingerprint: com.poyka.ripdpi.data.NetworkFingerprint,
    )

    private fun TestScope.buildStaleProxyExitEnv(): StaleProxyExitEnv {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = TestServiceStateStore()
        val events = mutableListOf<String>()
        val host = TestVpnServiceHost(backgroundScope)
        val initialFingerprint = sampleFingerprint()
        val newFingerprint = sampleFingerprint(dnsServers = listOf("8.8.8.8"))
        val resolver =
            TestConnectionPolicyResolver(
                sampleResolution(mode = Mode.VPN, policySignature = "initial"),
            ).also {
                it.enqueue(
                    sampleResolution(mode = Mode.VPN, policySignature = "initial"),
                    sampleResolution(mode = Mode.VPN, policySignature = "handover"),
                )
            }
        val runtimeRegistry = DefaultServiceRuntimeRegistry()
        val handoverMonitor = TestNetworkHandoverMonitor()
        val oldRuntime = DelayedStopVpnProxyRuntime(events)
        val newRuntime =
            TestProxyRuntime(events).apply {
                telemetry = telemetry.copy(listenerAddress = "127.0.0.1:18081")
            }
        val proxyFactory =
            object : RipDpiProxyFactory {
                private var calls = 0

                override fun create() =
                    when (calls++) {
                        0 -> oldRuntime
                        else -> newRuntime
                    }
            }
        val coordinator =
            buildStaleProxyExitCoordinator(
                dispatcher = dispatcher,
                store = store,
                events = events,
                host = host,
                initialFingerprint = initialFingerprint,
                resolver = resolver,
                runtimeRegistry = runtimeRegistry,
                handoverMonitor = handoverMonitor,
                proxyFactory = proxyFactory,
            )
        return StaleProxyExitEnv(
            coordinator = coordinator,
            store = store,
            handoverMonitor = handoverMonitor,
            runtimeRegistry = runtimeRegistry,
            oldRuntime = oldRuntime,
            newFingerprint = newFingerprint,
            initialFingerprint = initialFingerprint,
        )
    }

    private fun TestScope.buildStaleProxyExitCoordinator(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        store: TestServiceStateStore,
        events: MutableList<String>,
        host: TestVpnServiceHost,
        initialFingerprint: com.poyka.ripdpi.data.NetworkFingerprint,
        resolver: TestConnectionPolicyResolver,
        runtimeRegistry: DefaultServiceRuntimeRegistry,
        handoverMonitor: TestNetworkHandoverMonitor,
        proxyFactory: RipDpiProxyFactory,
    ): VpnServiceRuntimeCoordinator {
        val overrides = TestResolverOverrideStore()
        val clock = TestServiceClock(now = 1_000L)
        val bridgeFactory = TestTun2SocksBridgeFactory(TestTun2SocksBridge(events))
        val tunnelProvider =
            TestVpnTunnelSessionProvider(
                events = events,
                session = TestVpnTunnelSession(events = events),
            )
        return VpnServiceRuntimeCoordinator(
            vpnHost = host,
            connectionPolicyResolver = resolver,
            resolverOverrideStore = overrides,
            serviceRuntimeRegistry = runtimeRegistry,
            rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
            networkHandoverMonitor = handoverMonitor,
            policyHandoverEventStore = TestPolicyHandoverEventStore(),
            permissionWatchdog = TestPermissionWatchdog(),
            vpnProtectFailureMonitor = TestVpnProtectFailureMonitor(),
            vpnTunnelRuntime =
                VpnTunnelRuntime(
                    vpnHost = host,
                    appSettingsRepository = TestAppSettingsRepository(),
                    tun2SocksBridgeFactory = bridgeFactory,
                    vpnTunnelSessionProvider = tunnelProvider,
                ),
            resolverRefreshPlanner =
                VpnResolverRefreshPlanner(
                    connectionPolicyResolver = resolver,
                    resolverOverrideStore = overrides,
                ),
            encryptedDnsFailoverController =
                VpnEncryptedDnsFailoverController(
                    resolverOverrideStore = overrides,
                    networkDnsPathPreferenceStore = TestNetworkDnsPathPreferenceStore(),
                    networkDnsBlockedPathStore = TestNetworkDnsBlockedPathStore(),
                    networkFingerprintProvider = TestNetworkFingerprintProvider(initialFingerprint),
                    clock = clock,
                ),
            upstreamRelaySupervisor =
                UpstreamRelaySupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    relayFactory = TestRipDpiRelayFactory(),
                    naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                    relayProfileStore = TestRelayProfileStore(),
                    relayCredentialStore = TestRelayCredentialStore(),
                ),
            warpRuntimeSupervisor =
                WarpRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    warpFactory = TestRipDpiWarpFactory(),
                    runtimeConfigResolver = TestWarpRuntimeConfigResolver(),
                ),
            proxyRuntimeSupervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = proxyFactory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                ),
            statusReporter =
                ServiceStatusReporter(
                    mode = Mode.VPN,
                    sender = com.poyka.ripdpi.data.Sender.VPN,
                    serviceStateStore = store,
                    networkFingerprintProvider = TestNetworkFingerprintProvider(initialFingerprint),
                    telemetryFingerprintHasher = TestTelemetryFingerprintHasher(),
                    runtimeExperimentSelectionProvider =
                        object : RuntimeExperimentSelectionProvider {
                            override fun current(): RuntimeExperimentSelection = RuntimeExperimentSelection()
                        },
                    clock = clock,
                ),
            screenStateObserver = TestScreenStateObserver(),
            ioDispatcher = dispatcher,
            clock = clock,
        )
    }

    @Test
    fun stopPreventsPendingResolverRefreshFromRebuildingTunnel() =
        runTest {
            val env = newEnv()

            env.coordinator.start()
            runCurrent()

            val updatedSettings =
                AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setDnsMode(DnsModePlainUdp)
                    .setDnsIp("8.8.8.8")
                    .build()
            env.resolver.enqueue(
                sampleResolution(
                    mode = Mode.VPN,
                    settings = updatedSettings,
                    activeDns = updatedSettings.activeDnsSettings(),
                ),
            )

            env.coordinator.stop()
            runCurrent()
            advanceTimeBy(2_000L)
            repeat(4) { runCurrent() }

            assertEquals(AppStatus.Halted to Mode.VPN, env.store.status.value)
            assertEquals(1, env.bridgeFactory.bridge.startedConfigs.size)
            assertEquals(1, env.bridgeFactory.bridge.stopCount)
            assertEquals(1, env.factory.runtimes.size)
        }

    private fun plainDnsResolution(): ConnectionPolicyResolution {
        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDnsMode(DnsModePlainUdp)
                .setDnsIp("8.8.8.8")
                .build()
        return sampleResolution(mode = Mode.VPN, settings = settings, activeDns = settings.activeDnsSettings())
    }

    @Suppress("LongMethod")
    private fun TestScope.newEnv(
        fingerprint: com.poyka.ripdpi.data.NetworkFingerprint? = sampleFingerprint(),
        resolutions: List<com.poyka.ripdpi.services.ConnectionPolicyResolution> =
            listOf(sampleResolution(mode = Mode.VPN)),
        runtimeFactory: (MutableList<String>) -> TestProxyRuntime = { events -> TestProxyRuntime(events) },
    ): Env {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = mutableListOf<String>()
        val store = TestServiceStateStore()
        val host = TestVpnServiceHost(backgroundScope)
        val resolver = TestConnectionPolicyResolver(resolutions.first())
        resolver.enqueue(*resolutions.toTypedArray())
        val fingerprintProvider = TestNetworkFingerprintProvider(fingerprint)
        val factory = TestRipDpiProxyFactory { runtimeFactory(events) }
        val warpFactory = TestRipDpiWarpFactory { TestWarpRuntime(events) }
        val bridgeFactory = TestTun2SocksBridgeFactory(TestTun2SocksBridge(events))
        val tunnelProvider =
            TestVpnTunnelSessionProvider(
                events = events,
                session = TestVpnTunnelSession(events = events),
            )
        val runtimeRegistry = DefaultServiceRuntimeRegistry()
        val handoverMonitor = TestNetworkHandoverMonitor()
        val handoverEvents = TestPolicyHandoverEventStore()
        val permissionWatchdog = TestPermissionWatchdog()
        val vpnProtectFailureMonitor = TestVpnProtectFailureMonitor()
        val overrides = TestResolverOverrideStore()
        val preferredPaths = TestNetworkDnsPathPreferenceStore()
        val clock = TestServiceClock(now = 1_000L)
        val tunnelRuntime =
            VpnTunnelRuntime(
                vpnHost = host,
                appSettingsRepository = TestAppSettingsRepository(),
                tun2SocksBridgeFactory = bridgeFactory,
                vpnTunnelSessionProvider = tunnelProvider,
            )
        val coordinator =
            VpnServiceRuntimeCoordinator(
                vpnHost = host,
                connectionPolicyResolver = resolver,
                resolverOverrideStore = overrides,
                serviceRuntimeRegistry = runtimeRegistry,
                rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
                networkHandoverMonitor = handoverMonitor,
                policyHandoverEventStore = handoverEvents,
                permissionWatchdog = permissionWatchdog,
                vpnProtectFailureMonitor = vpnProtectFailureMonitor,
                vpnTunnelRuntime = tunnelRuntime,
                resolverRefreshPlanner =
                    VpnResolverRefreshPlanner(
                        connectionPolicyResolver = resolver,
                        resolverOverrideStore = overrides,
                    ),
                encryptedDnsFailoverController =
                    VpnEncryptedDnsFailoverController(
                        resolverOverrideStore = overrides,
                        networkDnsPathPreferenceStore = preferredPaths,
                        networkDnsBlockedPathStore = TestNetworkDnsBlockedPathStore(),
                        networkFingerprintProvider = fingerprintProvider,
                        clock = clock,
                    ),
                upstreamRelaySupervisor =
                    UpstreamRelaySupervisor(
                        scope = backgroundScope,
                        dispatcher = dispatcher,
                        relayFactory = TestRipDpiRelayFactory(),
                        naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                        relayProfileStore = TestRelayProfileStore(),
                        relayCredentialStore = TestRelayCredentialStore(),
                    ),
                warpRuntimeSupervisor =
                    WarpRuntimeSupervisor(
                        scope = backgroundScope,
                        dispatcher = dispatcher,
                        warpFactory = warpFactory,
                        runtimeConfigResolver = TestWarpRuntimeConfigResolver(),
                    ),
                proxyRuntimeSupervisor =
                    ProxyRuntimeSupervisor(
                        scope = backgroundScope,
                        dispatcher = dispatcher,
                        ripDpiProxyFactory = factory,
                        networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                    ),
                statusReporter =
                    ServiceStatusReporter(
                        mode = Mode.VPN,
                        sender = com.poyka.ripdpi.data.Sender.VPN,
                        serviceStateStore = store,
                        networkFingerprintProvider = fingerprintProvider,
                        telemetryFingerprintHasher = TestTelemetryFingerprintHasher(),
                        runtimeExperimentSelectionProvider =
                            object : RuntimeExperimentSelectionProvider {
                                override fun current(): RuntimeExperimentSelection = RuntimeExperimentSelection()
                            },
                        clock = clock,
                    ),
                screenStateObserver = TestScreenStateObserver(),
                ioDispatcher = dispatcher,
                clock = clock,
            )
        return Env(
            coordinator = coordinator,
            store = store,
            host = host,
            factory = factory,
            warpFactory = warpFactory,
            bridgeFactory = bridgeFactory,
            tunnelProvider = tunnelProvider,
            runtimeRegistry = runtimeRegistry,
            resolver = resolver,
            handoverMonitor = handoverMonitor,
            handoverEvents = handoverEvents,
            permissionWatchdog = permissionWatchdog,
            vpnProtectFailureMonitor = vpnProtectFailureMonitor,
            resolverOverrides = overrides,
            preferredPaths = preferredPaths,
            events = events,
        )
    }
}

private class DelayedStopVpnProxyRuntime(
    private val events: MutableList<String>,
) : com.poyka.ripdpi.core.RipDpiProxyRuntime {
    private val exitCode = CompletableDeferred<Int>()
    private val telemetry =
        NativeRuntimeSnapshot(
            source = "proxy",
            state = "running",
            health = "healthy",
            listenerAddress = "127.0.0.1:18080",
        )

    override suspend fun startProxy(preferences: RipDpiProxyPreferences): Int {
        events += "proxy:start"
        return exitCode.await()
    }

    override suspend fun awaitReady(timeoutMillis: Long) = Unit

    override suspend fun stopProxy() {
        events += "proxy:stop"
    }

    override suspend fun pollTelemetry(): NativeRuntimeSnapshot = telemetry

    override suspend fun updateNetworkSnapshot(snapshot: com.poyka.ripdpi.data.NativeNetworkSnapshot) = Unit

    fun complete(code: Int) {
        if (!exitCode.isCompleted) {
            exitCode.complete(code)
        }
    }
}
