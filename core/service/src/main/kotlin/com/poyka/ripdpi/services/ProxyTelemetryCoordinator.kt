package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryOutcome
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.toStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

internal class ProxyTelemetryCoordinator(
    private val host: ServiceCoordinatorHost,
    private val upstreamRelaySupervisor: UpstreamRelaySupervisor,
    private val warpRuntimeSupervisor: WarpRuntimeSupervisor,
    private val proxyRuntimeSupervisor: ProxyRuntimeSupervisor,
    private val statusReporter: ServiceStatusReporter,
    private val screenStateObserver: ScreenStateObserver,
    private val directPathPolicyTelemetryConsumer: DirectPathPolicyTelemetryConsumer,
    private val currentStatus: () -> ServiceStatus,
    private val currentSession: () -> ProxyRuntimeSession?,
    private val consumePendingNetworkHandoverClass: () -> String?,
    private val currentNetworkHandoverState: () -> String?,
) {
    private companion object {
        private const val TelemetryPollIntervalMs = 1_000L
        private const val TelemetryPollIntervalBackgroundMs = 5_000L
    }

    fun start(replaceTelemetryJob: ((suspend CoroutineScope.() -> Unit) -> Unit)) {
        replaceTelemetryJob {
            while (currentStatus() == ServiceStatus.Connected) {
                val telemetry = pollCurrentTelemetry()
                directPathPolicyTelemetryConsumer.consume(telemetry.proxyTelemetry)
                statusReporter.reportTelemetry(
                    activePolicy = currentSession()?.currentActiveConnectionPolicy,
                    consumePendingNetworkHandoverClass = { null },
                    currentNetworkHandoverState = currentNetworkHandoverState,
                    proxyTelemetry = telemetry.proxyTelemetry,
                    relayTelemetry = telemetry.relayTelemetry,
                    warpTelemetry = telemetry.warpTelemetry,
                    awgTelemetry = telemetry.awgTelemetry,
                    tunnelTelemetry = telemetry.tunnelTelemetry,
                    proxyTelemetryStatus = telemetry.proxyTelemetryStatus,
                    relayTelemetryStatus = telemetry.relayTelemetryStatus,
                    warpTelemetryStatus = telemetry.warpTelemetryStatus,
                    awgTelemetryStatus = telemetry.awgTelemetryStatus,
                    tunnelTelemetryStatus = telemetry.tunnelTelemetryStatus,
                    tunnelRecoveryRetryCount = 0,
                )
                if (statusReporter.startedAt != null && screenStateObserver.isInteractive.value) {
                    host.updateNotification(
                        tunnelStats = telemetry.proxyTelemetry.tunnelStats,
                        proxyTelemetry = telemetry.proxyTelemetry,
                    )
                }
                delay(nextTelemetryPollInterval())
            }
        }
    }

    private suspend fun pollCurrentTelemetry(): VpnTelemetrySnapshot {
        val proxyTelemetryOutcome = proxyRuntimeSupervisor.pollTelemetry()
        val relayTelemetryOutcome = upstreamRelaySupervisor.pollTelemetry()
        val warpTelemetryOutcome = warpRuntimeSupervisor.pollTelemetry()
        return VpnTelemetrySnapshot(
            proxyTelemetry = proxyTelemetryOutcome.snapshotOrIdle(source = "proxy"),
            proxyTelemetryStatus = proxyTelemetryOutcome.toStatus(),
            relayTelemetry = relayTelemetryOutcome.snapshotOrIdle(source = "relay"),
            relayTelemetryStatus = relayTelemetryOutcome.toStatus(),
            warpTelemetry = warpTelemetryOutcome.snapshotOrIdle(source = "warp"),
            warpTelemetryStatus = warpTelemetryOutcome.toStatus(),
            awgTelemetry = RuntimeTelemetryOutcome.NoData.snapshotOrIdle(source = "amneziawg"),
            awgTelemetryStatus = RuntimeTelemetryOutcome.NoData.toStatus(),
            tunnelTelemetry = pendingTunnelTelemetry(),
            tunnelTelemetryStatus = RuntimeTelemetryOutcome.NoData.toStatus(),
        )
    }

    private fun pendingTunnelTelemetry(): NativeRuntimeSnapshot =
        consumePendingNetworkHandoverClass()
            ?.let { classification ->
                NativeRuntimeSnapshot.idle(source = "tunnel").copy(
                    networkHandoverClass = classification,
                )
            }
            ?: NativeRuntimeSnapshot.idle(source = "tunnel")

    private fun nextTelemetryPollInterval(): Long =
        if (screenStateObserver.isInteractive.value) {
            TelemetryPollIntervalMs
        } else {
            TelemetryPollIntervalBackgroundMs
        }
}
