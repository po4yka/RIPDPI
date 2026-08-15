package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.NoopStartupJournal
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.StartupJournal
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.service.telemetry.RuntimeTelemetryProjection
import com.poyka.ripdpi.service.telemetry.RuntimeTelemetryStatuses
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class ServiceStatusReporter(
    private val mode: Mode,
    private val sender: Sender,
    private val serviceStateStore: ServiceStateStore,
    private val networkFingerprintProvider: NetworkFingerprintProvider,
    private val telemetryFingerprintHasher: TelemetryFingerprintHasher,
    private val runtimeExperimentSelectionProvider: RuntimeExperimentSelectionProvider,
    private val startupJournal: StartupJournal = NoopStartupJournal,
    private val clock: ServiceClock = SystemServiceClock,
) {
    // Sticky "foreign relay failed after connecting" signal. Set by the relay exit
    // handlers (via markForeignRelayFailed), cleared on the next relay start and on
    // service stop (via clearForeignRelayFailed). Read on every telemetry update so the
    // signal survives the per-poll snapshot rebuild without being silently dropped.
    private val foreignRelayFailed = AtomicBoolean(false)
    private val journalStatus = AtomicReference<ServiceStatus?>(null)

    fun markForeignRelayFailed() {
        foreignRelayFailed.set(true)
    }

    fun clearForeignRelayFailed() {
        foreignRelayFailed.set(false)
    }

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
        awgTelemetry: NativeRuntimeSnapshot? = null,
        telemetryStatuses: RuntimeTelemetryStatuses = RuntimeTelemetryStatuses(),
        failureReason: FailureReason? = null,
        xrayProviderSnapshot: com.poyka.ripdpi.data.xray.XrayProviderSnapshot? = null,
    ) {
        statusPersistence.applyStatus(newStatus, failureReason)
        val priorJournalStatus = journalStatus.getAndSet(newStatus)
        if (priorJournalStatus != newStatus) {
            when (newStatus) {
                ServiceStatus.Connected -> {
                    startupJournal.recordServiceStarted(mode = mode, occurredAtMillis = clock.nowMillis())
                }

                ServiceStatus.Failed -> {
                    startupJournal.recordServiceFailed(
                        mode = mode,
                        failureKind = failureReason.startupJournalKind(),
                        occurredAtMillis = clock.nowMillis(),
                    )
                }

                ServiceStatus.Disconnected -> {
                    Unit
                }
            }
        }
        val currentTelemetry = serviceStateStore.telemetry.value
        serviceStateStore.updateTelemetry(
            telemetryProjection
                .statusTelemetry(
                    newStatus = newStatus,
                    currentTelemetry = currentTelemetry,
                    activePolicy = activePolicy,
                    consumePendingNetworkHandoverClass = consumePendingNetworkHandoverClass,
                    currentNetworkHandoverState = currentNetworkHandoverState,
                    tunnelRecoveryRetryCount = tunnelRecoveryRetryCount,
                    relayTelemetry = relayTelemetry,
                    warpTelemetry = warpTelemetry,
                    awgTelemetry = awgTelemetry,
                    telemetryStatuses = telemetryStatuses,
                    failureReason = failureReason,
                    xrayProviderSnapshot = xrayProviderSnapshot,
                ).copy(relayFailed = foreignRelayFailed.get()),
        )
    }

    fun reportTelemetry(
        activePolicy: ActiveConnectionPolicy?,
        consumePendingNetworkHandoverClass: () -> String?,
        currentNetworkHandoverState: () -> String?,
        proxyTelemetry: NativeRuntimeSnapshot,
        relayTelemetry: NativeRuntimeSnapshot,
        warpTelemetry: NativeRuntimeSnapshot,
        awgTelemetry: NativeRuntimeSnapshot,
        tunnelTelemetry: NativeRuntimeSnapshot,
        telemetryStatuses: RuntimeTelemetryStatuses,
        tunnelRecoveryRetryCount: Long,
        failureReason: FailureReason? = null,
        xrayProviderSnapshot: com.poyka.ripdpi.data.xray.XrayProviderSnapshot? = null,
    ) {
        val currentTelemetry = serviceStateStore.telemetry.value

        serviceStateStore.updateTelemetry(
            telemetryProjection
                .liveTelemetry(
                    currentTelemetry = currentTelemetry,
                    activePolicy = activePolicy,
                    consumePendingNetworkHandoverClass = consumePendingNetworkHandoverClass,
                    currentNetworkHandoverState = currentNetworkHandoverState,
                    proxyTelemetry = proxyTelemetry,
                    relayTelemetry = relayTelemetry,
                    warpTelemetry = warpTelemetry,
                    awgTelemetry = awgTelemetry,
                    tunnelTelemetry = tunnelTelemetry,
                    proxyTelemetryStatus = telemetryStatuses.proxy ?: RuntimeTelemetryStatus.NoData,
                    relayTelemetryStatus = telemetryStatuses.relay ?: RuntimeTelemetryStatus.NoData,
                    warpTelemetryStatus = telemetryStatuses.warp ?: RuntimeTelemetryStatus.NoData,
                    awgTelemetryStatus = telemetryStatuses.awg ?: RuntimeTelemetryStatus.NoData,
                    tunnelTelemetryStatus = telemetryStatuses.tunnel ?: RuntimeTelemetryStatus.NoData,
                    tunnelRecoveryRetryCount = tunnelRecoveryRetryCount,
                    failureReason = failureReason,
                    xrayProviderSnapshot = xrayProviderSnapshot,
                ).copy(relayFailed = foreignRelayFailed.get()),
        )
    }
}

private fun FailureReason?.startupJournalKind(): String =
    when (this) {
        is FailureReason.NativeError -> "native_error"
        FailureReason.TunnelEstablishmentFailed -> "tunnel_establishment_failed"
        is FailureReason.WarpProvisioningFailed -> "warp_provisioning_failed"
        is FailureReason.WarpEndpointUnavailable -> "warp_endpoint_unavailable"
        is FailureReason.WarpRuntimeFailed -> "warp_runtime_failed"
        is FailureReason.RelayFingerprintPolicyRejected -> "relay_fingerprint_policy_rejected"
        is FailureReason.RelayConfigRejected -> "relay_config_rejected"
        is FailureReason.InitialTransportSelectionFailed -> "initial_transport_selection_failed"
        is FailureReason.Unexpected -> "unexpected"
        is FailureReason.PermissionLost -> "permission_lost"
        null -> "unavailable"
    }
