package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
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
            val exits = mutableListOf<Result<Int>>()

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
            assertEquals(0, exits.single().getOrNull())
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

            assertTrue(result.exceptionOrNull() is IOException)
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
            val exits = mutableListOf<Result<Int>>()

            supervisor.start(RipDpiProxyUIPreferences()) { exits += it }
            runtime.complete(19)
            runCurrent()
            advanceUntilIdle()

            assertEquals(19, exits.single().getOrNull())
        }

    @Test
    fun pollTelemetryReturnsNullWhenRuntimeThrows() =
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

            assertNull(telemetry)
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

            assertTrue(error is IllegalArgumentException)
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
