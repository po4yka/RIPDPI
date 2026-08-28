package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.ServiceStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VpnTelemetryFailureHandlerTest {
    @Test
    fun `handover started after polling discards captured failure`() =
        runTest {
            val state = HandlerState(VpnRuntimeSession())
            val boundary = VpnTelemetryFailureBoundary.capture(state, null)
            val captured = genericTunnelEngineFailureTelemetry()
            val handler =
                VpnTelemetryFailureHandler(
                    HandlerDependencies(),
                    state,
                    object : VpnTelemetryFailureCallbacks {
                        override suspend fun updateStatus(
                            status: ServiceStatus,
                            failureReason: FailureReason?,
                        ) = error("handover owns status")

                        override suspend fun failAndStopService(
                            failureReason: FailureReason,
                            guard: RuntimeStopGuard?,
                            beforeFailureStatus: suspend () -> Unit,
                        ): Boolean = error("handover must not be stopped by captured failure")

                        override suspend fun stopService(guard: RuntimeStopGuard?): Boolean =
                            error("handover must not be stopped by captured failure")
                    },
                )
            state.handoverRestarting = true

            assertEquals(VpnTelemetryFailureHandling.DiscardStale, handler.handleOutcome(captured, boundary))
        }

    @Test
    fun `rejected generic failure is stale discard instead of publishable continue`() =
        runTest {
            val session = VpnRuntimeSession()
            var failureTelemetryReported = false
            val callbacks =
                object : VpnTelemetryFailureCallbacks {
                    override suspend fun updateStatus(
                        status: ServiceStatus,
                        failureReason: FailureReason?,
                    ) = error("stale generic failure must not publish status")

                    override suspend fun failAndStopService(
                        failureReason: FailureReason,
                        guard: RuntimeStopGuard?,
                        beforeFailureStatus: suspend () -> Unit,
                    ): Boolean {
                        failureTelemetryReported = false
                        return false
                    }

                    override suspend fun stopService(guard: RuntimeStopGuard?): Boolean =
                        error("generic tunnel failure must use failAndStopService")
                }
            val handler = VpnTelemetryFailureHandler(HandlerDependencies(), HandlerState(session), callbacks)

            val outcome =
                handler.handleOutcome(
                    genericTunnelEngineFailureTelemetry(),
                    VpnTelemetryFailureBoundary(session = session, xrayGeneration = null),
                )

            assertEquals(VpnTelemetryFailureHandling.DiscardStale, outcome)
            assertFalse(failureTelemetryReported)
        }
}

private class HandlerDependencies : VpnTelemetryRuntimeDependencies {
    override val host: VpnCoordinatorHost get() = error("Unexpected host access")
    override val ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    override val mutex: Mutex get() = error("Unexpected mutex access")
    override val vpnProtectFailureMonitor: VpnProtectFailureMonitor get() = error("Unexpected protect monitor access")
    override val vpnTunnelRuntime: VpnTunnelRuntime get() = error("Unexpected tunnel runtime access")
    override val upstreamRelaySupervisor: UpstreamRelaySupervisor get() = error("Unexpected relay telemetry access")
    override val warpRuntimeSupervisor: WarpRuntimeSupervisor get() = error("Unexpected warp telemetry access")
    override val amneziaWgRuntimeSupervisor: AmneziaWgRuntimeSupervisor get() =
        error("Unexpected awg telemetry access")
    override val proxyRuntimeSupervisor: ProxyRuntimeSupervisor get() = error("Unexpected proxy telemetry access")
    override val screenStateObserver: ScreenStateObserver =
        object : ScreenStateObserver {
            override val isInteractive = MutableStateFlow(true)
        }
    override val telemetryReporter: VpnRuntimeTelemetryReporter get() = error("Stale failure snapshot was published")
    override val xrayController: XrayProviderSessionController? = null
}

private class HandlerState(
    private val session: VpnRuntimeSession,
) : VpnTelemetryStateAccess {
    var handoverRestarting = false

    override fun status(): ServiceStatus = ServiceStatus.Connected

    override fun stopping(): Boolean = handoverRestarting

    override fun runtimeSession(): VpnRuntimeSession = session

    override fun currentLocalProxyEndpoint(): LocalProxyEndpoint? = null

    override fun currentNetworkHandoverState(): String? = null

    override fun applyPendingNetworkHandoverClass(snapshot: NativeRuntimeSnapshot): NativeRuntimeSnapshot = snapshot
}

private fun genericTunnelEngineFailureTelemetry(): VpnTelemetrySnapshot =
    VpnTelemetrySnapshot(
        proxyTelemetry = NativeRuntimeSnapshot.idle("proxy"),
        proxyTelemetryStatus = RuntimeTelemetryStatus.NoData,
        relayTelemetry = NativeRuntimeSnapshot.idle("relay"),
        relayTelemetryStatus = RuntimeTelemetryStatus.NoData,
        warpTelemetry = NativeRuntimeSnapshot.idle("warp"),
        warpTelemetryStatus = RuntimeTelemetryStatus.NoData,
        awgTelemetry = NativeRuntimeSnapshot.idle("amneziawg"),
        awgTelemetryStatus = RuntimeTelemetryStatus.NoData,
        tunnelTelemetry = NativeRuntimeSnapshot.idle("tunnel"),
        tunnelTelemetryStatus =
            RuntimeTelemetryStatus(
                state = RuntimeTelemetryState.EngineError,
                message = "stale tunnel failure",
            ),
    )
