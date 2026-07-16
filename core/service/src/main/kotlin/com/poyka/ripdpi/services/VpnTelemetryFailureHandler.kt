package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.ServiceStatus
import kotlinx.coroutines.launch

internal class VpnTelemetryFailureHandler(
    private val dependencies: VpnTelemetryRuntimeDependencies,
    private val state: VpnTelemetryStateAccess,
    private val callbacks: VpnTelemetryFailureCallbacks,
) {
    suspend fun handle(telemetry: VpnTelemetrySnapshot): Boolean {
        val telemetryFailure = telemetry.failureReason()
        val tunnelStoppedUnexpectedly =
            dependencies.vpnTunnelRuntime.isForwarding && telemetry.tunnelTelemetry.state != "running"
        val session = state.runtimeSession()
        val dnsFailoverPending =
            session != null &&
                !session.encryptedDnsFailoverState.exhausted &&
                session.currentDns?.isEncrypted == true

        val shouldStop =
            !state.stopping() &&
                (telemetryFailure != null || tunnelStoppedUnexpectedly) &&
                !dnsFailoverPending
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
