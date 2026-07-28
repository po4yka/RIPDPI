package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.RuntimeTelemetryOutcome
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.toStatus
import com.poyka.ripdpi.service.telemetry.RuntimeTelemetryStatuses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference

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
    private val refreshDestinationRoutingPolicy: suspend () -> Unit = {},
) {
    private companion object {
        private const val TelemetryPollIntervalMs = 1_000L
        private const val TelemetryPollIntervalBackgroundMs = 5_000L
    }

    private val activeEvidenceCollector = AtomicReference<DataPlaneEvidenceCollector?>()

    fun start(replaceTelemetryJob: ((suspend CoroutineScope.() -> Unit) -> Unit)) {
        val evidenceCollector = newEvidenceCollector()
        activeEvidenceCollector.set(evidenceCollector)
        replaceTelemetryJob {
            while (currentStatus() == ServiceStatus.Connected) {
                val telemetry = pollCurrentTelemetry(evidenceCollector)
                if (activeEvidenceCollector.get() !== evidenceCollector) return@replaceTelemetryJob
                reportTelemetry(telemetry)
                if (statusReporter.startedAt != null && screenStateObserver.isInteractive.value) {
                    host.updateNotification(
                        tunnelStats = telemetry.proxyTelemetry.tunnelStats,
                        proxyTelemetry = telemetry.proxyTelemetry,
                    )
                }
                delay(nextTelemetryPollInterval())
                refreshDestinationRoutingPolicy()
            }
        }
    }

    suspend fun captureFinalTelemetry() =
        captureFinalDataPlaneEvidence(
            activeCollector = activeEvidenceCollector,
            capture = { evidenceCollector -> pollCurrentTelemetry(evidenceCollector, finalCapture = true) },
            publish = ::reportTelemetry,
        )

    private fun newEvidenceCollector(): DataPlaneEvidenceCollector =
        DataPlaneEvidenceCollector(
            mode = Mode.Proxy,
            proxyEvidenceProvider = proxyRuntimeSupervisor::pollForwardingEvidence,
        )

    private suspend fun pollCurrentTelemetry(
        evidenceCollector: DataPlaneEvidenceCollector,
        finalCapture: Boolean = false,
    ): VpnTelemetrySnapshot {
        val proxyTelemetryOutcome = proxyRuntimeSupervisor.pollTelemetry()
        val relayTelemetryOutcome = upstreamRelaySupervisor.pollTelemetry()
        val warpTelemetryOutcome = warpRuntimeSupervisor.pollTelemetry()
        val snapshot =
            VpnTelemetrySnapshot(
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
        return if (finalCapture) {
            evidenceCollector.finalizeAndEnrich(snapshot)
        } else {
            evidenceCollector.enrich(snapshot)
        }
    }

    private suspend fun reportTelemetry(telemetry: VpnTelemetrySnapshot) {
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
            telemetryStatuses =
                RuntimeTelemetryStatuses(
                    proxy = telemetry.proxyTelemetryStatus,
                    relay = telemetry.relayTelemetryStatus,
                    warp = telemetry.warpTelemetryStatus,
                    awg = telemetry.awgTelemetryStatus,
                    tunnel = telemetry.tunnelTelemetryStatus,
                ),
            tunnelRecoveryRetryCount = 0,
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
