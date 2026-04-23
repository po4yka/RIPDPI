package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.data.RuntimeTelemetryOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val TestLocalProxyAuth = "alpha-123"

@OptIn(ExperimentalCoroutinesApi::class)
class ProxyRuntimeSupervisorTest {
    @Test
    fun startAndStopManageRuntimeLifecycle() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val runtime = TestProxyRuntime()
            val factory = TestRipDpiProxyFactory { runtime }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = factory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )
            val exits = mutableListOf<SupervisorExitCause>()

            val endpoint = supervisor.start(RipDpiProxyUIPreferences()) { exits += it }

            assertSame(runtime, supervisor.runtime)
            assertEquals(1, runtime.updatedSnapshots)
            assertEquals("127.0.0.1", endpoint.host)
            assertEquals(1080, endpoint.port)

            supervisor.stop()
            advanceUntilIdle()

            assertNull(supervisor.runtime)
            assertEquals(1, runtime.stopCount)
            assertEquals(1, exits.size)
            assertEquals(SupervisorExitCause.ExpectedStop, exits.single())
        }

    @Test
    fun startupFailureCleansUpRuntimeFields() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val runtime = TestProxyRuntime().apply { startFailure = IOException("proxy boom") }
            val factory = TestRipDpiProxyFactory { runtime }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = factory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )

            val result =
                runCatching {
                    supervisor.start(RipDpiProxyUIPreferences()) {}
                }

            val error = result.exceptionOrNull()
            assertTrue(error is SupervisorStartupFailureException)
            assertTrue((error as SupervisorStartupFailureException).exitCause.throwable is IOException)
            assertNull(supervisor.runtime)
        }

    @Test
    fun nonZeroExitIsReportedToCallback() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val runtime = TestProxyRuntime()
            val factory = TestRipDpiProxyFactory { runtime }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = factory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )
            val exits = mutableListOf<SupervisorExitCause>()

            supervisor.start(RipDpiProxyUIPreferences()) { exits += it }
            runtime.complete(19)
            runCurrent()
            advanceUntilIdle()

            assertEquals(SupervisorExitCause.Crash(19), exits.single())
        }

    @Test
    fun repeatedStartStopRecoversAfterScriptedCrash() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val factory = TestRipDpiProxyFactory()
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = factory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )
            val exits = mutableListOf<SupervisorExitCause>()

            supervisor.start(RipDpiProxyUIPreferences()) { exits += it }
            supervisor.stop()
            advanceUntilIdle()

            supervisor.start(RipDpiProxyUIPreferences()) { exits += it }
            supervisor.stop()
            advanceUntilIdle()

            assertEquals(2, factory.runtimes.size)
            assertEquals(1, factory.runtimes[0].stopCount)
            assertEquals(1, factory.runtimes[1].stopCount)
            assertEquals(
                listOf(
                    SupervisorExitCause.ExpectedStop,
                    SupervisorExitCause.ExpectedStop,
                ),
                exits,
            )
        }

    @Test
    fun pollTelemetryReturnsEngineErrorWhenRuntimeThrows() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val runtime = TestProxyRuntime()
            val factory = TestRipDpiProxyFactory { runtime }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = factory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )

            supervisor.start(RipDpiProxyUIPreferences()) {}
            runtime.telemetryFailure = IOException("telemetry crash")

            val telemetry = supervisor.pollTelemetry()

            assertTrue(telemetry is RuntimeTelemetryOutcome.EngineError)
            assertEquals("telemetry crash", (telemetry as RuntimeTelemetryOutcome.EngineError).message)
        }

    @Test
    fun pollTelemetryReturnsNoDataWhenRuntimeIsMissing() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = TestRipDpiProxyFactory(),
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )

            val telemetry = supervisor.pollTelemetry()

            assertEquals(RuntimeTelemetryOutcome.NoData, telemetry)
        }

    @Test
    fun stopWithNullRuntimeIsSafeNoOp() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = TestRipDpiProxyFactory(),
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )

            supervisor.stop()

            assertNull(supervisor.runtime)
        }

    @Test
    fun networkSnapshotCaptureFailureIsSwallowedAndProxyKeepsRunning() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val runtime = TestProxyRuntime()
            val factory = TestRipDpiProxyFactory { runtime }
            val snapshotProvider =
                TestNativeNetworkSnapshotProvider(captureFailure = RuntimeException("capture failed"))
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = factory,
                    networkSnapshotProvider = snapshotProvider,
                )

            supervisor.start(RipDpiProxyUIPreferences()) {}

            assertNotNull(supervisor.runtime)
            assertEquals(0, runtime.updatedSnapshots)
        }

    @Test
    fun awaitReadyFailureCleansUpRuntimeAndPropagatesOriginalError() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val readinessError = IOException("readiness timeout")
            val runtime = TestProxyRuntime().apply { awaitReadyFailure = readinessError }
            val factory = TestRipDpiProxyFactory { runtime }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = factory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )

            val error =
                runCatching {
                    supervisor.start(RipDpiProxyUIPreferences()) {}
                }.exceptionOrNull()

            assertNotNull(error)
            assertTrue(error is SupervisorStartupFailureException)
            assertNull(supervisor.runtime)
        }

    @Test
    fun startupFailsWhenReadyProxyDoesNotReportListenerAddress() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val runtime =
                TestProxyRuntime().apply {
                    telemetry = telemetry.copy(listenerAddress = null)
                }
            val factory = TestRipDpiProxyFactory { runtime }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = factory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )

            val error =
                runCatching {
                    supervisor.start(RipDpiProxyUIPreferences()) {}
                }.exceptionOrNull()

            assertTrue(error is SupervisorStartupFailureException)
            assertTrue((error as SupervisorStartupFailureException).exitCause.throwable is IllegalArgumentException)
            assertNull(supervisor.runtime)
            assertEquals(1, runtime.stopCount)
        }

    @Test
    fun resolvedEndpointCarriesVpnSessionCredentials() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val runtime = TestProxyRuntime()
            val factory = TestRipDpiProxyFactory { runtime }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = factory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )

            val endpoint =
                supervisor.start(
                    RipDpiProxyUIPreferences().withSessionLocalProxyOverrides(
                        listenPortOverride = 0,
                        authToken = TestLocalProxyAuth,
                    ),
                ) {}

            assertEquals("127.0.0.1", endpoint.host)
            assertEquals(1080, endpoint.port)
            assertEquals(VpnLocalProxyUsername, endpoint.username)
            assertEquals(TestLocalProxyAuth, endpoint.password)
        }

    @Test
    fun detachClearsRuntimeWithoutStopping() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val runtime = TestProxyRuntime()
            val factory = TestRipDpiProxyFactory { runtime }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = factory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )

            supervisor.start(RipDpiProxyUIPreferences()) {}
            supervisor.detach()

            assertNull(supervisor.runtime)
            assertEquals(0, runtime.stopCount)
        }
}
