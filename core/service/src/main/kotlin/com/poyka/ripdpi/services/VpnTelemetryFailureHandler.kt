package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.RuntimeTelemetryState
import com.poyka.ripdpi.data.ServiceStatus
import kotlinx.coroutines.launch

internal class VpnTelemetryFailureHandler(
    private val dependencies: VpnTelemetryRuntimeDependencies,
    private val state: VpnTelemetryStateAccess,
    private val callbacks: VpnTelemetryFailureCallbacks,
) {
    suspend fun handle(telemetry: VpnTelemetrySnapshot): Boolean {
        val xray = dependencies.xrayController
        val generation = xray?.failedGeneration
        if (generation != null && !state.stopping()) {
            val session = state.runtimeSession()
            val reason = FailureReason.NativeError("Xray engine or local listener exited unexpectedly")
            dependencies.host.serviceScope.launch(dependencies.ioDispatcher) {
                callbacks.stopService(
                    RuntimeStopGuard(
                        isCurrent = { state.runtimeSession() === session && xray.ownsGeneration(generation) },
                        failureReason = reason,
                    ),
                )
            }
            // A handover may invalidate the queued generation. Keep monitoring until
            // an accepted stop cancels this loop through the normal lifecycle path.
            return false
        }
        return handleTunnelFailure(telemetry)
    }

    private suspend fun handleTunnelFailure(telemetry: VpnTelemetrySnapshot): Boolean {
        val telemetryFailure = telemetry.failureReason()
        val tunnelStoppedUnexpectedly =
            telemetry.tunnelTelemetryStatus.state == RuntimeTelemetryState.Snapshot &&
                dependencies.vpnTunnelRuntime.isForwarding &&
                telemetry.tunnelTelemetry.state != "running"
        val shouldStop =
            !state.stopping() &&
                (telemetryFailure != null || tunnelStoppedUnexpectedly)
        if (!shouldStop) {
            return false
        }
        val failureReason =
            telemetryFailure ?: FailureReason.NativeError(
                telemetry.tunnelTelemetry.lastError ?: "Tunnel exited unexpectedly",
            )
        dependencies.telemetryReporter.report(telemetry, state, failureReason)
        callbacks.updateStatus(ServiceStatus.Failed, failureReason)
        dependencies.host.serviceScope.launch(dependencies.ioDispatcher) { callbacks.stopService() }
        return true
    }
}
