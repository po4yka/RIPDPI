package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.RuntimeTelemetryState

internal data class VpnTelemetryFailureBoundary(
    val session: VpnRuntimeSession?,
    val xrayGeneration: Long?,
) {
    fun providerStopGuard(
        controller: XrayProviderSessionController?,
        failureReason: FailureReason,
        currentSession: () -> VpnRuntimeSession?,
    ): RuntimeStopGuard? =
        session?.let { capturedSession ->
            RuntimeStopGuard(
                isCurrent = {
                    currentSession() === capturedSession &&
                        when (val generation = xrayGeneration) {
                            null -> controller?.currentGenerationIfActive() == null
                            else -> controller?.ownsGeneration(generation) == true
                        }
                },
                failureReason = failureReason,
            )
        }

    companion object {
        fun capture(
            state: VpnTelemetryStateAccess,
            xrayController: XrayProviderSessionController?,
        ): VpnTelemetryFailureBoundary =
            VpnTelemetryFailureBoundary(
                session = state.runtimeSession(),
                xrayGeneration = xrayController?.currentGenerationIfActive(),
            )
    }
}

internal enum class VpnTelemetryFailureHandling {
    Continue,
    DiscardStale,
    StopAccepted,
}

internal class VpnTelemetryFailureHandler(
    private val dependencies: VpnTelemetryRuntimeDependencies,
    private val state: VpnTelemetryStateAccess,
    private val callbacks: VpnTelemetryFailureCallbacks,
) {
    suspend fun handleOutcome(
        telemetry: VpnTelemetrySnapshot,
        boundary: VpnTelemetryFailureBoundary = VpnTelemetryFailureBoundary.capture(state, dependencies.xrayController),
    ): VpnTelemetryFailureHandling {
        val xray = dependencies.xrayController
        val generation = xray?.failedGeneration
        if (generation != null && !state.stopping()) {
            val session = state.runtimeSession()
            val reason = FailureReason.NativeError("Xray engine or local listener exited unexpectedly")
            val guard =
                RuntimeStopGuard(
                    isCurrent = { state.runtimeSession() === session && xray.ownsGeneration(generation) },
                    failureReason = reason,
                )
            return if (guard.isCurrent() && callbacks.stopService(guard)) {
                VpnTelemetryFailureHandling.StopAccepted
            } else {
                VpnTelemetryFailureHandling.DiscardStale
            }
        }
        return handleTunnelFailure(telemetry, boundary)
    }

    private suspend fun handleTunnelFailure(
        telemetry: VpnTelemetrySnapshot,
        boundary: VpnTelemetryFailureBoundary,
    ): VpnTelemetryFailureHandling {
        if (state.stopping()) return VpnTelemetryFailureHandling.DiscardStale
        val telemetryFailure = telemetry.failureReason()
        val tunnelStoppedUnexpectedly =
            telemetry.tunnelTelemetryStatus.state == RuntimeTelemetryState.Snapshot &&
                dependencies.vpnTunnelRuntime.isForwarding &&
                telemetry.tunnelTelemetry.state != "running"
        val shouldStop = telemetryFailure != null || tunnelStoppedUnexpectedly
        return if (!shouldStop) {
            VpnTelemetryFailureHandling.Continue
        } else {
            val failureReason =
                telemetryFailure ?: FailureReason.NativeError(
                    telemetry.tunnelTelemetry.lastError ?: "Tunnel exited unexpectedly",
                )
            val guard =
                boundary.providerStopGuard(
                    controller = dependencies.xrayController,
                    failureReason = failureReason,
                    currentSession = state::runtimeSession,
                )
            if (
                callbacks.failAndStopService(failureReason, guard) {
                    dependencies.telemetryReporter.report(telemetry, state, failureReason)
                }
            ) {
                VpnTelemetryFailureHandling.StopAccepted
            } else {
                VpnTelemetryFailureHandling.DiscardStale
            }
        }
    }
}
