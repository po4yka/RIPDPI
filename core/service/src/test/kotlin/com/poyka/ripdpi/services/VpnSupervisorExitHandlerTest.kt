package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Regression guard: a VPN relay exit must close the TUN before failover. */
@OptIn(ExperimentalCoroutinesApi::class)
class VpnSupervisorExitHandlerTest {
    private class Harness(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
    ) {
        val failAndStopReasons = mutableListOf<FailureReason?>()
        var foreignRelayFailureCount = 0
            private set

        private val upstreamRelaySupervisor =
            UpstreamRelaySupervisor(
                scope = scope,
                dispatcher = dispatcher,
                relayFactory = TestRipDpiRelayFactory(),
                naiveProxyRuntimeFactory = TestNaiveProxyRuntimeFactory(),
                relayProfileStore = TestRelayProfileStore(),
                selectorRelayRuntimeProfileResolver = TestSelectorRelayRuntimeProfileResolver(),
                relayCredentialStore = TestRelayCredentialStore(),
            )
        private val warpRuntimeSupervisor =
            WarpRuntimeSupervisor(
                scope = scope,
                dispatcher = dispatcher,
                warpFactory = TestRipDpiWarpFactory(),
                runtimeConfigResolver = TestWarpRuntimeConfigResolver(),
            )
        private val amneziaWgRuntimeSupervisor =
            AmneziaWgRuntimeSupervisor(
                scope = scope,
                dispatcher = dispatcher,
                amneziaWgFactory = NoOpRipDpiAmneziaWgFactory(),
                runtimeConfigResolver = TestAmneziaWgRuntimeConfigResolver(),
                activeRegistry = ActiveAwgRuntimeRegistry(),
            )
        private val proxyRuntimeSupervisor =
            ProxyRuntimeSupervisor(
                scope = scope,
                dispatcher = dispatcher,
                ripDpiProxyFactory = TestRipDpiProxyFactory { TestProxyRuntime() },
                networkSnapshotProvider = TestNativeNetworkSnapshotProvider(),
            )

        val handler =
            VpnSupervisorExitHandler(
                proxyRuntimeStack =
                    SharedProxyRuntimeStack(
                        upstreamRelaySupervisor = upstreamRelaySupervisor,
                        warpRuntimeSupervisor = warpRuntimeSupervisor,
                        amneziaWgRuntimeSupervisor = amneziaWgRuntimeSupervisor,
                        proxyRuntimeSupervisor = proxyRuntimeSupervisor,
                    ),
                upstreamRelaySupervisor = upstreamRelaySupervisor,
                warpRuntimeSupervisor = warpRuntimeSupervisor,
                amneziaWgRuntimeSupervisor = amneziaWgRuntimeSupervisor,
                markForeignRelayFailed = { foreignRelayFailureCount += 1 },
                failAndStop = { reason -> failAndStopReasons += reason },
            )
    }

    @Test
    fun `unexpected relay crash fails closed before failover`() =
        runTest {
            val harness = Harness(this, StandardTestDispatcher(testScheduler))

            harness.handler.handleRelayExit(SupervisorExitCause.Crash(7))
            advanceUntilIdle()

            assertEquals(listOf(FailureReason.NativeError("Relay exited with code 7")), harness.failAndStopReasons)
            assertEquals(1, harness.foreignRelayFailureCount)
        }

    @Test
    fun `relay startup failure after connect fails closed`() =
        runTest {
            val harness = Harness(this, StandardTestDispatcher(testScheduler))

            harness.handler.handleRelayExit(SupervisorExitCause.StartupFailure(IOException("relay boom")))
            advanceUntilIdle()

            assertTrue(harness.failAndStopReasons.single() is FailureReason.NativeError)
        }

    @Test
    fun `expected relay stop leaves VPN running`() =
        runTest {
            val harness = Harness(this, StandardTestDispatcher(testScheduler))

            harness.handler.handleRelayExit(SupervisorExitCause.ExpectedStop)
            advanceUntilIdle()

            assertTrue(harness.failAndStopReasons.isEmpty())
            assertEquals(0, harness.foreignRelayFailureCount)
        }
}
