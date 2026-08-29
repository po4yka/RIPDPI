package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.LocalNetworkPermission
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.service.runtime.proxy.ProxyRuntimeSupervisorBundle
import com.poyka.ripdpi.service.runtime.proxy.ProxyServiceRuntimeCoordinator
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

@OptIn(ExperimentalCoroutinesApi::class)
class ProxyServiceLocalNetworkRevocationTest {
    @Test
    fun localNetworkRevocationStopsOnlyDependentProxyRuntime() =
        runTest {
            val env = newEnv(localNetworkDependent = true)

            env.coordinator.start()
            runCurrent()
            env.permissionWatchdog.emit(
                PermissionChangeEvent(PermissionChangeEvent.KIND_LOCAL_NETWORK, detectedAt = 2_000L),
            )
            repeat(3) { runCurrent() }

            assertEquals(AppStatus.Halted to Mode.Proxy, env.store.status.value)
            val failure = env.store.eventHistory.last() as ServiceEvent.Failed
            assertEquals(FailureReason.PermissionLost(LocalNetworkPermission), failure.reason)
            assertNull(env.runtimeRegistry.current(Mode.Proxy))
        }

    @Test
    fun localNetworkRevocationLeavesPublicProxyRuntimeRunning() =
        runTest {
            val env = newEnv(localNetworkDependent = false)

            env.coordinator.start()
            runCurrent()
            env.permissionWatchdog.emit(
                PermissionChangeEvent(PermissionChangeEvent.KIND_LOCAL_NETWORK, detectedAt = 2_000L),
            )
            repeat(3) { runCurrent() }

            assertEquals(AppStatus.Running to Mode.Proxy, env.store.status.value)
            assertTrue(env.store.eventHistory.none { it is ServiceEvent.Failed })
            assertNotNull(env.runtimeRegistry.current(Mode.Proxy))
        }

    private data class Env(
        val coordinator: ProxyServiceRuntimeCoordinator,
        val store: TestServiceStateStore,
        val runtimeRegistry: ServiceRuntimeRegistry,
        val permissionWatchdog: TestPermissionWatchdog,
    )

    private fun TestScope.newEnv(localNetworkDependent: Boolean): Env {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = TestServiceStateStore()
        val runtimeRegistry = DefaultServiceRuntimeRegistry()
        val permissionWatchdog = TestPermissionWatchdog()
        val coordinator =
            ProxyServiceRuntimeCoordinator(
                host = TestProxyServiceHost(backgroundScope),
                connectionPolicyResolver =
                    TestConnectionPolicyResolver(
                        sampleResolution(mode = Mode.Proxy, localNetworkDependent = localNetworkDependent),
                    ),
                serviceRuntimeRegistry = runtimeRegistry,
                rememberedNetworkPolicyStore = TestRememberedNetworkPolicyStore(),
                networkHandoverMonitor = TestNetworkHandoverMonitor(),
                policyHandoverEventStore = TestPolicyHandoverEventStore(),
                permissionWatchdog = permissionWatchdog,
                supervisors = proxySupervisors(dispatcher),
                autolearnActivationReceiptPublisher =
                    testAutolearnActivationReceiptPublisher(AutolearnActivationRecorder { _ -> }),
                statusReporter =
                    ServiceStatusReporter(
                        mode = Mode.Proxy,
                        sender = com.poyka.ripdpi.data.Sender.Proxy,
                        serviceStateStore = store,
                        networkFingerprintProvider = TestNetworkFingerprintProvider(sampleFingerprint()),
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
        return Env(coordinator, store, runtimeRegistry, permissionWatchdog)
    }

    private fun TestScope.proxySupervisors(dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        ProxyRuntimeSupervisorBundle(
            upstreamRelaySupervisor =
                UpstreamRelaySupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    relayFactory = TestRipDpiRelayFactory(),
                    naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                    runtimeConfigResolver = TestUpstreamRelayRuntimeConfigResolver(),
                ),
            warpRuntimeSupervisor =
                WarpRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    warpFactory = TestRipDpiWarpFactory(),
                    runtimeConfigResolver = TestWarpRuntimeConfigResolver(),
                ),
            amneziaWgRuntimeSupervisor =
                AmneziaWgRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    amneziaWgFactory = NoOpRipDpiAmneziaWgFactory(),
                    runtimeConfigResolver = TestAmneziaWgRuntimeConfigResolver(),
                ),
            proxyRuntimeSupervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = TestRipDpiProxyFactory(),
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                ),
        )
}
