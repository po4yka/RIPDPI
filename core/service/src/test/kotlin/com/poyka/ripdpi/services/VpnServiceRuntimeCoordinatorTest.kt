package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.DnsProviderGoogle
import com.poyka.ripdpi.data.EncryptedDnsProtocolDoh
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.activeDnsSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class VpnServiceRuntimeCoordinatorTest {
    private data class Env(
        val coordinator: VpnServiceRuntimeCoordinator,
        val store: TestServiceStateStore,
        val host: TestVpnServiceHost,
        val factory: TestRipDpiProxyFactory,
        val bridgeFactory: TestTun2SocksBridgeFactory,
        val tunnelProvider: TestVpnTunnelSessionProvider,
        val runtimeRegistry: ServiceRuntimeRegistry,
        val resolver: TestConnectionPolicyResolver,
        val handoverMonitor: TestNetworkHandoverMonitor,
        val handoverEvents: TestPolicyHandoverEventStore,
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

    private fun plainDnsResolution(): ConnectionPolicyResolution {
        val settings =
            AppSettingsSerializer.defaultValue
                .toBuilder()
                .setDnsMode(DnsModePlainUdp)
                .setDnsIp("8.8.8.8")
                .build()
        return sampleResolution(mode = Mode.VPN, settings = settings, activeDns = settings.activeDnsSettings())
    }

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
        val bridgeFactory = TestTun2SocksBridgeFactory(TestTun2SocksBridge(events))
        val tunnelProvider =
            TestVpnTunnelSessionProvider(
                events = events,
                session = TestVpnTunnelSession(events = events),
            )
        val runtimeRegistry = DefaultServiceRuntimeRegistry()
        val handoverMonitor = TestNetworkHandoverMonitor()
        val handoverEvents = TestPolicyHandoverEventStore()
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
                permissionWatchdog = TestPermissionWatchdog(),
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
            bridgeFactory = bridgeFactory,
            tunnelProvider = tunnelProvider,
            runtimeRegistry = runtimeRegistry,
            resolver = resolver,
            handoverMonitor = handoverMonitor,
            handoverEvents = handoverEvents,
            resolverOverrides = overrides,
            preferredPaths = preferredPaths,
            events = events,
        )
    }
}
