package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.service.telemetry.RuntimeTelemetryStatuses

internal class VpnRuntimeTelemetryReporter(
    private val host: VpnCoordinatorHost,
    private val statusReporter: ServiceStatusReporter,
    private val screenStateObserver: ScreenStateObserver,
    private val directPathPolicyTelemetryConsumer: DirectPathPolicyTelemetryConsumer,
    private val vpnTunnelRuntime: VpnTunnelRuntime,
    /**
     * Optional Xray provider seam. When the active session runs the Xray
     * provider, its derived (secret-free) snapshot is folded into the telemetry
     * on the EXISTING loop — no new timer. Null / native path leaves it out.
     */
    private val xrayController: XrayProviderSessionController? = null,
) {
    suspend fun report(
        telemetry: VpnTelemetrySnapshot,
        state: VpnTelemetryStateAccess,
        failureReason: FailureReason? = null,
    ) {
        directPathPolicyTelemetryConsumer.consume(telemetry.proxyTelemetry)
        statusReporter.reportTelemetry(
            activePolicy = state.runtimeSession()?.currentActiveConnectionPolicy,
            consumePendingNetworkHandoverClass = { null },
            currentNetworkHandoverState = { state.currentNetworkHandoverState() },
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
            tunnelRecoveryRetryCount = vpnTunnelRuntime.tunnelRecoveryRetryCount,
            failureReason = failureReason,
            xrayProviderSnapshot = xrayController?.currentSnapshotOrNull(),
        )
        if (statusReporter.startedAt != null && screenStateObserver.isInteractive.value) {
            host.updateNotification(
                tunnelStats = telemetry.tunnelTelemetry.tunnelStats,
                proxyTelemetry = telemetry.proxyTelemetry,
            )
        }
    }
}
