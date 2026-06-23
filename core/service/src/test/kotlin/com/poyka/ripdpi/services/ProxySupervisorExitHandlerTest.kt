package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeNetworkSnapshot
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.service.telemetry.RuntimeTelemetryStatuses
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Regression guard for audit finding P1-1: a foreign relay that dies AFTER connecting must
 * raise a sticky "relay failed" signal (so the Home actuator flips Locked -> Degraded) while
 * keeping the base proxy runtime alive — it must NOT silently swallow the failure, and it
 * must NOT tear down the service. An expected stop must clear / do nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProxySupervisorExitHandlerTest {
    private class Harness(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
    ) {
        val store = DefaultServiceStateStore()
        val statusReporter =
            ServiceStatusReporter(
                mode = Mode.Proxy,
                sender = Sender.Proxy,
                serviceStateStore = store,
                networkFingerprintProvider = TestNetworkFingerprintProvider(),
                telemetryFingerprintHasher = TestTelemetryFingerprintHasher(),
                runtimeExperimentSelectionProvider =
                    object : RuntimeExperimentSelectionProvider {
                        override fun current(): RuntimeExperimentSelection = RuntimeExperimentSelection()
                    },
                clock = TestServiceClock(now = 1_000L),
            )
        val statusUpdates = mutableListOf<Pair<ServiceStatus, FailureReason?>>()
        var stopServiceCount = 0
            private set

        val handler =
            ProxySupervisorExitHandler(
                host = TestProxyServiceHost(scope),
                ioDispatcher = dispatcher,
                upstreamRelaySupervisor =
                    UpstreamRelaySupervisor(
                        scope = scope,
                        dispatcher = dispatcher,
                        relayFactory = TestRipDpiRelayFactory(),
                        naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                        relayProfileStore = TestRelayProfileStore(),
                        relayCredentialStore = TestRelayCredentialStore(),
                    ),
                warpRuntimeSupervisor =
                    WarpRuntimeSupervisor(
                        scope = scope,
                        dispatcher = dispatcher,
                        warpFactory = TestRipDpiWarpFactory(),
                        runtimeConfigResolver = TestWarpRuntimeConfigResolver(),
                    ),
                amneziaWgRuntimeSupervisor =
                    AmneziaWgRuntimeSupervisor(
                        scope = scope,
                        dispatcher = dispatcher,
                        amneziaWgFactory = NoOpRipDpiAmneziaWgFactory(),
                        runtimeConfigResolver = TestAmneziaWgRuntimeConfigResolver(),
                    ),
                proxyRuntimeSupervisor =
                    ProxyRuntimeSupervisor(
                        scope = scope,
                        dispatcher = dispatcher,
                        ripDpiProxyFactory = TestRipDpiProxyFactory { TestProxyRuntime() },
                        networkSnapshotProvider =
                            object : NativeNetworkSnapshotProvider {
                                override fun capture(): NativeNetworkSnapshot =
                                    NativeNetworkSnapshot(transport = "wifi")
                            },
                    ),
                updateStatus = { status, reason -> statusUpdates += status to reason },
                markForeignRelayFailed = statusReporter::markForeignRelayFailed,
                stopService = { stopServiceCount += 1 },
            )

        // Force the sticky signal onto a fresh live snapshot so the test can read relayFailed.
        fun currentRelayFailed(): Boolean {
            statusReporter.reportStatus(
                newStatus = ServiceStatus.Connected,
                activePolicy = null,
                consumePendingNetworkHandoverClass = { null },
                currentNetworkHandoverState = { null },
                tunnelRecoveryRetryCount = 0L,
                telemetryStatuses = RuntimeTelemetryStatuses(),
                failureReason = null,
            )
            return store.telemetry.value.relayFailed
        }
    }

    @Test
    fun `unexpected relay crash raises the sticky signal without stopping the base`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val harness = Harness(backgroundScope, dispatcher)

            harness.handler.handleRelayExit(SupervisorExitCause.Crash(7))
            advanceUntilIdle()

            assertTrue("foreign relay failure must set the sticky signal", harness.currentRelayFailed())
            assertTrue("the base must not be marked Failed", harness.statusUpdates.isEmpty())
            assertEquals("the service must not be stopped", 0, harness.stopServiceCount)
        }

    @Test
    fun `startup failure after connect also raises the sticky signal`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val harness = Harness(backgroundScope, dispatcher)

            harness.handler.handleRelayExit(SupervisorExitCause.StartupFailure(IOException("relay boom")))
            advanceUntilIdle()

            assertTrue(harness.currentRelayFailed())
            assertEquals(0, harness.stopServiceCount)
        }

    @Test
    fun `expected stop does not raise the sticky signal`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val harness = Harness(backgroundScope, dispatcher)

            harness.handler.handleRelayExit(SupervisorExitCause.ExpectedStop)
            advanceUntilIdle()

            assertFalse("an expected stop must not flag a degraded foreign exit", harness.currentRelayFailed())
            assertEquals(0, harness.stopServiceCount)
        }
}
