package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.service.telemetry.RuntimeTelemetryProjection

internal class ServiceStatusReporter(
    private val mode: Mode,
    private val sender: Sender,
    private val serviceStateStore: ServiceStateStore,
    private val networkFingerprintProvider: NetworkFingerprintProvider,
    private val telemetryFingerprintHasher: TelemetryFingerprintHasher,
    private val runtimeExperimentSelectionProvider: RuntimeExperimentSelectionProvider,
    private val clock: ServiceClock = SystemServiceClock,
) {
    private val statusPersistence =
        ServiceStatusPersistence(
            mode = mode,
            sender = sender,
            serviceStateStore = serviceStateStore,
        )
    private val telemetryProjection =
        RuntimeTelemetryProjection(
            mode = mode,
            networkFingerprintProvider = networkFingerprintProvider,
            telemetryFingerprintHasher = telemetryFingerprintHasher,
            runtimeExperimentSelectionProvider = runtimeExperimentSelectionProvider,
            clock = clock,
        )

    val startedAt: Long?
        get() = serviceStateStore.telemetry.value.serviceStartedAt

    fun reportStatus(
        newStatus: ServiceStatus,
        activePolicy: ActiveConnectionPolicy?,
        consumePendingNetworkHandoverClass: () -> String?,
        currentNetworkHandoverState: () -> String?,
        tunnelRecoveryRetryCount: Long,
        relayTelemetry: NativeRuntimeSnapshot? = null,
        warpTelemetry: NativeRuntimeSnapshot? = null,
        proxyTelemetryStatus: RuntimeTelemetryStatus? = null,
        relayTelemetryStatus: RuntimeTelemetryStatus? = null,
        warpTelemetryStatus: RuntimeTelemetryStatus? = null,
        tunnelTelemetryStatus: RuntimeTelemetryStatus? = null,
        failureReason: FailureReason? = null,
        xrayProviderSnapshot: com.poyka.ripdpi.data.xray.XrayProviderSnapshot? = null,
    ) {
        statusPersistence.applyStatus(newStatus, failureReason)
        val currentTelemetry = serviceStateStore.telemetry.value
        serviceStateStore.updateTelemetry(
            telemetryProjection.statusTelemetry(
                newStatus = newStatus,
                currentTelemetry = currentTelemetry,
                activePolicy = activePolicy,
                consumePendingNetworkHandoverClass = consumePendingNetworkHandoverClass,
                currentNetworkHandoverState = currentNetworkHandoverState,
                tunnelRecoveryRetryCount = tunnelRecoveryRetryCount,
                relayTelemetry = relayTelemetry,
                warpTelemetry = warpTelemetry,
                proxyTelemetryStatus = proxyTelemetryStatus,
                relayTelemetryStatus = relayTelemetryStatus,
                warpTelemetryStatus = warpTelemetryStatus,
                tunnelTelemetryStatus = tunnelTelemetryStatus,
                failureReason = failureReason,
                xrayProviderSnapshot = xrayProviderSnapshot,
            ),
        )
    }

    fun reportTelemetry(
        activePolicy: ActiveConnectionPolicy?,
        consumePendingNetworkHandoverClass: () -> String?,
        currentNetworkHandoverState: () -> String?,
        proxyTelemetry: NativeRuntimeSnapshot,
        relayTelemetry: NativeRuntimeSnapshot,
        warpTelemetry: NativeRuntimeSnapshot,
        tunnelTelemetry: NativeRuntimeSnapshot,
        proxyTelemetryStatus: RuntimeTelemetryStatus,
        relayTelemetryStatus: RuntimeTelemetryStatus,
        warpTelemetryStatus: RuntimeTelemetryStatus,
        tunnelTelemetryStatus: RuntimeTelemetryStatus,
        tunnelRecoveryRetryCount: Long,
        failureReason: FailureReason? = null,
        xrayProviderSnapshot: com.poyka.ripdpi.data.xray.XrayProviderSnapshot? = null,
    ) {
        val currentTelemetry = serviceStateStore.telemetry.value

        serviceStateStore.updateTelemetry(
            telemetryProjection.liveTelemetry(
                currentTelemetry = currentTelemetry,
                activePolicy = activePolicy,
                consumePendingNetworkHandoverClass = consumePendingNetworkHandoverClass,
                currentNetworkHandoverState = currentNetworkHandoverState,
                proxyTelemetry = proxyTelemetry,
                relayTelemetry = relayTelemetry,
                warpTelemetry = warpTelemetry,
                tunnelTelemetry = tunnelTelemetry,
                proxyTelemetryStatus = proxyTelemetryStatus,
                relayTelemetryStatus = relayTelemetryStatus,
                warpTelemetryStatus = warpTelemetryStatus,
                tunnelTelemetryStatus = tunnelTelemetryStatus,
                tunnelRecoveryRetryCount = tunnelRecoveryRetryCount,
                failureReason = failureReason,
                xrayProviderSnapshot = xrayProviderSnapshot,
            ),
        )
    }
}
