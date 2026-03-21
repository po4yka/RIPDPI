package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
                    runtimeFactory = {
                        TestProxyRuntime().apply { startFailure = IOException("proxy boom") }
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
            val newFingerprint = sampleFingerprint(dnsServers = listOf("8.8.8.8"))
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
            assertEquals("handover", env.handoverEvents.published.single().policySignature)
        }

    private fun TestScope.newEnv(
        fingerprint: com.poyka.ripdpi.data.NetworkFingerprint? = sampleFingerprint(),
        resolutions: List<com.poyka.ripdpi.services.ConnectionPolicyResolution> = listOf(sampleResolution(mode = Mode.Proxy)),
        runtimeFactory: () -> TestProxyRuntime = { TestProxyRuntime() },
    ): Env {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = TestServiceStateStore()
        val host = TestProxyServiceHost(backgroundScope)
        val resolver = TestConnectionPolicyResolver(resolutions.first())
        resolver.enqueue(*resolutions.toTypedArray())
        val fingerprintProvider = TestNetworkFingerprintProvider(fingerprint)
        val factory = TestRipDpiProxyFactory(runtimeFactory)
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
                proxyRuntimeSupervisor =
                    ProxyRuntimeSupervisor(
                        scope = backgroundScope,
                        dispatcher = dispatcher,
                        ripDpiProxyFactory = factory,
                        networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                    ),
                statusReporter =
                    ServiceStatusReporter(
                        mode = Mode.Proxy,
                        sender = com.poyka.ripdpi.data.Sender.Proxy,
                        serviceStateStore = store,
                        networkFingerprintProvider = fingerprintProvider,
                        telemetryFingerprintHasher = TestTelemetryFingerprintHasher(),
                        clock = TestServiceClock(now = 1_000L),
                    ),
                ioDispatcher = dispatcher,
                clock = TestServiceClock(now = 1_000L),
            )
        return Env(
            coordinator = coordinator,
            store = store,
            host = host,
            factory = factory,
            runtimeRegistry = runtimeRegistry,
            handoverMonitor = handoverMonitor,
            handoverEvents = handoverEvents,
            resolver = resolver,
        )
    }
}
