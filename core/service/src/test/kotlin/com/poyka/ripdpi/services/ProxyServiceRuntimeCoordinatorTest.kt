package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyFactory
import com.poyka.ripdpi.core.RipDpiProxyPreferences
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiWarpConfig
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.WarpRouteModeRules
import kotlinx.coroutines.CompletableDeferred
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
class ProxyServiceRuntimeCoordinatorTest {
    private data class Env(
        val coordinator: ProxyServiceRuntimeCoordinator,
        val store: TestServiceStateStore,
        val host: TestProxyServiceHost,
        val factory: TestRipDpiProxyFactory,
        val warpFactory: TestRipDpiWarpFactory,
        val events: MutableList<String>,
        val runtimeRegistry: ServiceRuntimeRegistry,
        val handoverMonitor: TestNetworkHandoverMonitor,
        val handoverEvents: TestPolicyHandoverEventStore,
        val resolver: TestConnectionPolicyResolver,
    )

    @Test
    fun successfulStartPublishesRunningState() =
        runTest {
            val env = newEnv()

            env.coordinator.start()
            runCurrent()

            assertEquals(AppStatus.Running to Mode.Proxy, env.store.status.value)
            assertNotNull(env.runtimeRegistry.current(Mode.Proxy))
            assertEquals(1, env.factory.runtimes.size)
        }

    @Test
    fun successfulStartRunsWarpBeforeProxyWhenWarpRoutingEnabled() =
        runTest {
            val env =
                newEnv(
                    resolutions =
                        listOf(
                            sampleResolution(
                                mode = Mode.Proxy,
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

            assertEquals(listOf("warp:start", "proxy:start"), env.events.take(2))
            assertEquals(1, env.warpFactory.runtimes.size)
            assertEquals(1, env.factory.runtimes.size)
        }

    @Test
    fun duplicateStartIsIgnored() =
        runTest {
            val env = newEnv()

            env.coordinator.start()
            env.coordinator.start()
            runCurrent()

            assertEquals(1, env.factory.runtimes.size)
            assertEquals(1, env.resolver.calls.size)
        }

    @Test
    fun startupFailureBeforeReadyEmitsFailureAndHalts() =
        runTest {
            val env =
                newEnv(
                    runtimeFactory = { events ->
                        TestProxyRuntime(events).apply { startFailure = IOException("proxy boom") }
                    },
                )

            env.coordinator.start()
            runCurrent()

            assertEquals(AppStatus.Halted to Mode.Proxy, env.store.status.value)
            assertTrue(env.store.eventHistory.single() is ServiceEvent.Failed)
            assertNull(env.runtimeRegistry.current(Mode.Proxy))
            assertEquals(0, env.factory.lastRuntime.stopCount)
        }

    @Test
    fun nonZeroProxyExitEmitsFailureThenStops() =
        runTest {
            val env = newEnv()

            env.coordinator.start()
            runCurrent()
            env.factory.lastRuntime.complete(17)
            repeat(3) { runCurrent() }

            assertEquals(AppStatus.Halted to Mode.Proxy, env.store.status.value)
            assertTrue(env.store.eventHistory.any { it is ServiceEvent.Failed })
            assertNull(env.runtimeRegistry.current(Mode.Proxy))
        }

    @Test
    fun explicitStopIsIdempotent() =
        runTest {
            val env = newEnv()

            env.coordinator.start()
            runCurrent()

            val runtime = env.factory.lastRuntime
            env.coordinator.stop()
            env.coordinator.stop()
            runCurrent()

            assertEquals(1, runtime.stopCount)
            assertEquals(AppStatus.Halted to Mode.Proxy, env.store.status.value)
        }

    @Test
    fun handoverRestartPublishesPolicyEvent() =
        runTest {
            val initialFingerprint = sampleFingerprint()
            val newFingerprint =
                sampleFingerprint(dnsServers = listOf("8.8.8.8")).copy(
                    networkValidated = false,
                    captivePortalDetected = false,
                )
            val env =
                newEnv(
                    fingerprint = initialFingerprint,
                    resolutions =
                        listOf(
                            sampleResolution(mode = Mode.Proxy, policySignature = "initial"),
                            sampleResolution(mode = Mode.Proxy, policySignature = "handover"),
                        ),
                )

            env.coordinator.start()
            runCurrent()
            val firstRuntime = env.factory.lastRuntime

            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = initialFingerprint,
                    currentFingerprint = newFingerprint,
                    classification = "link_refresh",
                    occurredAt = 2_000L,
                ),
            )
            repeat(3) { runCurrent() }

            assertEquals(2, env.factory.runtimes.size)
            assertEquals(1, firstRuntime.stopCount)
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
    fun handoverFailureEmitsFailedStatusAndHalts() =
        runTest {
            val initialFingerprint = sampleFingerprint()
            val newFingerprint = sampleFingerprint(dnsServers = listOf("8.8.8.8"))
            var callCount = 0
            val env =
                newEnv(
                    fingerprint = initialFingerprint,
                    resolutions =
                        listOf(
                            sampleResolution(mode = Mode.Proxy, policySignature = "initial"),
                            sampleResolution(mode = Mode.Proxy, policySignature = "handover"),
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
                    classification = "link_refresh",
                    occurredAt = 2_000L,
                ),
            )
            // Exhaust exponential backoff retries: 2s + 4s + 8s + 16s = 30s
            advanceTimeBy(31_000L)
            repeat(5) { runCurrent() }

            assertEquals(AppStatus.Halted to Mode.Proxy, env.store.status.value)
            assertTrue(env.store.eventHistory.any { it is ServiceEvent.Failed })
        }

    private data class StaleReplacementEnv(
        val coordinator: ProxyServiceRuntimeCoordinator,
        val store: TestServiceStateStore,
        val handoverMonitor: TestNetworkHandoverMonitor,
        val runtimeRegistry: DefaultServiceRuntimeRegistry,
        val oldRuntime: DelayedStopProxyRuntime,
        val initialFingerprint: com.poyka.ripdpi.data.NetworkFingerprint,
        val newFingerprint: com.poyka.ripdpi.data.NetworkFingerprint,
    )

    private fun TestScope.buildStaleReplacementCoordinator(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        store: TestServiceStateStore,
        initialFingerprint: com.poyka.ripdpi.data.NetworkFingerprint,
        resolver: TestConnectionPolicyResolver,
        runtimeRegistry: DefaultServiceRuntimeRegistry,
        handoverMonitor: TestNetworkHandoverMonitor,
        proxyFactory: RipDpiProxyFactory,
    ): ProxyServiceRuntimeCoordinator =
        ProxyServiceRuntimeCoordinator(
            host = TestProxyServiceHost(backgroundScope),
            connectionPolicyResolver = resolver,
            serviceRuntimeRegistry = runtimeRegistry,
            rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
            networkHandoverMonitor = handoverMonitor,
            policyHandoverEventStore = TestPolicyHandoverEventStore(),
            permissionWatchdog = TestPermissionWatchdog(),
            supervisors =
                ProxyRuntimeSupervisorBundle(
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
                            networkSnapshotProvider =
                                object : NativeNetworkSnapshotProvider {
                                    override fun capture(): NativeNetworkSnapshot =
                                        NativeNetworkSnapshot(transport = "wifi")
                                },
                        ),
                ),
            statusReporter =
                ServiceStatusReporter(
                    mode = Mode.Proxy,
                    sender = com.poyka.ripdpi.data.Sender.Proxy,
                    serviceStateStore = store,
                    networkFingerprintProvider = TestNetworkFingerprintProvider(initialFingerprint),
                    telemetryFingerprintHasher = TestTelemetryFingerprintHasher(),
                    runtimeExperimentSelectionProvider =
                        object : RuntimeExperimentSelectionProvider {
                            override fun current(): RuntimeExperimentSelection = RuntimeExperimentSelection()
                        },
                    clock = TestServiceClock(now = 1_000L),
                ),
            screenStateObserver = TestScreenStateObserver(),
            ioDispatcher = dispatcher,
            clock = TestServiceClock(now = 1_000L),
        )

    private fun TestScope.buildStaleReplacementEnv(): StaleReplacementEnv {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = TestServiceStateStore()
        val events = mutableListOf<String>()
        val initialFingerprint = sampleFingerprint()
        val newFingerprint = sampleFingerprint(dnsServers = listOf("8.8.8.8"))
        val resolver =
            TestConnectionPolicyResolver(
                sampleResolution(mode = Mode.Proxy, policySignature = "initial"),
            ).also {
                it.enqueue(
                    sampleResolution(mode = Mode.Proxy, policySignature = "initial"),
                    sampleResolution(mode = Mode.Proxy, policySignature = "handover"),
                )
            }
        val runtimeRegistry = DefaultServiceRuntimeRegistry()
        val handoverMonitor = TestNetworkHandoverMonitor()
        val oldRuntime = DelayedStopProxyRuntime(events)
        val newRuntime = TestProxyRuntime(events)
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
            buildStaleReplacementCoordinator(
                dispatcher = dispatcher,
                store = store,
                initialFingerprint = initialFingerprint,
                resolver = resolver,
                runtimeRegistry = runtimeRegistry,
                handoverMonitor = handoverMonitor,
                proxyFactory = proxyFactory,
            )
        return StaleReplacementEnv(
            coordinator = coordinator,
            store = store,
            handoverMonitor = handoverMonitor,
            runtimeRegistry = runtimeRegistry,
            oldRuntime = oldRuntime,
            initialFingerprint = initialFingerprint,
            newFingerprint = newFingerprint,
        )
    }

    @Test
    fun staleSupersededProxyExitDoesNotHaltReplacementSession() =
        runTest {
            val env = buildStaleReplacementEnv()

            env.coordinator.start()
            runCurrent()

            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = env.initialFingerprint,
                    currentFingerprint = env.newFingerprint,
                    classification = "link_refresh",
                    occurredAt = 2_000L,
                ),
            )
            advanceTimeBy(5_000L)
            repeat(4) { runCurrent() }

            env.oldRuntime.complete(17)
            repeat(3) { runCurrent() }

            assertEquals(AppStatus.Running to Mode.Proxy, env.store.status.value)
            assertTrue(env.store.eventHistory.none { it is ServiceEvent.Failed })
            assertEquals(1, env.oldRuntime.stopCount)
            assertNotNull(env.runtimeRegistry.current(Mode.Proxy))
        }

    @Test
    fun handoverRestartStopsSupersededWarpRuntimeExactlyOnce() =
        runTest {
            val initialFingerprint = sampleFingerprint()
            val newFingerprint = sampleFingerprint(dnsServers = listOf("8.8.8.8"))
            val env =
                newEnv(
                    fingerprint = initialFingerprint,
                    resolutions =
                        listOf(
                            sampleResolution(
                                mode = Mode.Proxy,
                                policySignature = "initial",
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
                            sampleResolution(
                                mode = Mode.Proxy,
                                policySignature = "handover",
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

            env.handoverMonitor.emit(
                NetworkHandoverEvent(
                    previousFingerprint = initialFingerprint,
                    currentFingerprint = newFingerprint,
                    classification = "transport_switch",
                    occurredAt = 2_000L,
                ),
            )
            repeat(4) { runCurrent() }

            assertEquals(2, env.warpFactory.runtimes.size)
            assertEquals(1, env.warpFactory.runtimes[0].stopCount)
            assertEquals(0, env.warpFactory.runtimes[1].stopCount)
            assertEquals(2, env.factory.runtimes.size)
            assertEquals(1, env.factory.runtimes[0].stopCount)
            assertEquals(0, env.factory.runtimes[1].stopCount)
            assertEquals(AppStatus.Running to Mode.Proxy, env.store.status.value)
        }

    private fun TestScope.newEnv(
        fingerprint: com.poyka.ripdpi.data.NetworkFingerprint? = sampleFingerprint(),
        resolutions: List<com.poyka.ripdpi.services.ConnectionPolicyResolution> =
            listOf(sampleResolution(mode = Mode.Proxy)),
        runtimeFactory: (MutableList<String>) -> TestProxyRuntime = { events -> TestProxyRuntime(events) },
    ): Env {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = mutableListOf<String>()
        val store = TestServiceStateStore()
        val host = TestProxyServiceHost(backgroundScope)
        val resolver = TestConnectionPolicyResolver(resolutions.first())
        resolver.enqueue(*resolutions.toTypedArray())
        val fingerprintProvider = TestNetworkFingerprintProvider(fingerprint)
        val factory = TestRipDpiProxyFactory { runtimeFactory(events) }
        val warpFactory = TestRipDpiWarpFactory { TestWarpRuntime(events) }
        val runtimeRegistry = DefaultServiceRuntimeRegistry()
        val handoverMonitor = TestNetworkHandoverMonitor()
        val handoverEvents = TestPolicyHandoverEventStore()
        val coordinator =
            ProxyServiceRuntimeCoordinator(
                host = host,
                connectionPolicyResolver = resolver,
                serviceRuntimeRegistry = runtimeRegistry,
                rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
                networkHandoverMonitor = handoverMonitor,
                policyHandoverEventStore = handoverEvents,
                permissionWatchdog = TestPermissionWatchdog(),
                supervisors =
                    ProxyRuntimeSupervisorBundle(
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
                    ),
                statusReporter =
                    ServiceStatusReporter(
                        mode = Mode.Proxy,
                        sender = com.poyka.ripdpi.data.Sender.Proxy,
                        serviceStateStore = store,
                        networkFingerprintProvider = fingerprintProvider,
                        telemetryFingerprintHasher = TestTelemetryFingerprintHasher(),
                        runtimeExperimentSelectionProvider =
                            object : RuntimeExperimentSelectionProvider {
                                override fun current(): RuntimeExperimentSelection = RuntimeExperimentSelection()
                            },
                        clock = TestServiceClock(now = 1_000L),
                    ),
                screenStateObserver = TestScreenStateObserver(),
                ioDispatcher = dispatcher,
                clock = TestServiceClock(now = 1_000L),
            )
        return Env(
            coordinator = coordinator,
            store = store,
            host = host,
            factory = factory,
            warpFactory = warpFactory,
            events = events,
            runtimeRegistry = runtimeRegistry,
            handoverMonitor = handoverMonitor,
            handoverEvents = handoverEvents,
            resolver = resolver,
        )
    }
}

private class DelayedStopProxyRuntime(
    private val events: MutableList<String>,
) : com.poyka.ripdpi.core.RipDpiProxyRuntime {
    private val exitCode = CompletableDeferred<Int>()

    var stopCount: Int = 0
        private set
    private val telemetry =
        com.poyka.ripdpi.data.NativeRuntimeSnapshot(
            source = "proxy",
            state = "running",
            health = "healthy",
            listenerAddress = "127.0.0.1:1080",
        )

    override suspend fun startProxy(preferences: RipDpiProxyPreferences): Int {
        events += "proxy:start"
        return exitCode.await()
    }

    override suspend fun awaitReady(timeoutMillis: Long) = Unit

    override suspend fun stopProxy() {
        stopCount += 1
        events += "proxy:stop"
    }

    override suspend fun pollTelemetry() = telemetry

    override suspend fun updateNetworkSnapshot(snapshot: NativeNetworkSnapshot) = Unit

    fun complete(code: Int) {
        if (!exitCode.isCompleted) {
            exitCode.complete(code)
        }
    }
}
