package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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

            supervisor.start(RipDpiProxyUIPreferences()) { exits += it }

            assertSame(runtime, supervisor.runtime)
            assertEquals(1, runtime.updatedSnapshots)

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
            val runtime = TestProxyRuntime().apply { telemetryFailure = IOException("telemetry crash") }
            val factory = TestRipDpiProxyFactory { runtime }
            val supervisor =
                ProxyRuntimeSupervisor(
                    scope = backgroundScope,
                    dispatcher = dispatcher,
                    ripDpiProxyFactory = factory,
                    networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
                )

            supervisor.start(RipDpiProxyUIPreferences()) {}

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
}
