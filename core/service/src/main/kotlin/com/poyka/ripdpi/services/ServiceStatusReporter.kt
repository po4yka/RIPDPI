package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.RuntimeFieldTelemetry
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.data.deriveRuntimeFieldTelemetry
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy

internal class ServiceStatusReporter(
    private val mode: Mode,
    private val sender: Sender,
    private val serviceStateStore: ServiceStateStore,
    private val networkFingerprintProvider: NetworkFingerprintProvider,
    private val telemetryFingerprintHasher: TelemetryFingerprintHasher,
    private val runtimeExperimentSelectionProvider: RuntimeExperimentSelectionProvider,
    private val clock: ServiceClock = SystemServiceClock,
) {
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
        failureReason: FailureReason? = null,
    ) {
        val currentTelemetry = serviceStateStore.telemetry.value
        val proxyTelemetry = statusSnapshot(newStatus, source = "proxy", currentTelemetry.proxyTelemetry)
        val tunnelTelemetry =
            applyPendingNetworkHandoverClass(
                statusSnapshot(newStatus, source = "tunnel", currentTelemetry.tunnelTelemetry),
                consumePendingNetworkHandoverClass,
            )
        val effectiveRelayTelemetry =
            relayTelemetry
                ?: statusSnapshot(newStatus, source = "relay", currentTelemetry.relayTelemetry)
        val effectiveWarpTelemetry =
            warpTelemetry
                ?: statusSnapshot(newStatus, source = "warp", currentTelemetry.warpTelemetry)
        val (winningTcpStrategyFamily, winningQuicStrategyFamily, winningDnsStrategyFamily) =
            currentWinningFamilies(activePolicy, currentTelemetry.runtimeFieldTelemetry)
        val appStatus =
            when (newStatus) {
                ServiceStatus.Connected -> AppStatus.Running

                ServiceStatus.Failed,
                ServiceStatus.Disconnected,
                -> AppStatus.Halted
            }

        if (newStatus == ServiceStatus.Failed) {
            serviceStateStore.emitFailed(
                sender,
                failureReason ?: FailureReason.Unexpected(IllegalStateException("Unknown failure")),
            )
        }

        serviceStateStore.setStatus(appStatus, mode)
        serviceStateStore.updateTelemetry(
            ServiceTelemetrySnapshot(
                mode = mode,
                status = appStatus,
                tunnelStats = tunnelStatsFor(mode, proxyTelemetry, tunnelTelemetry),
                proxyTelemetry = enrichRuntimeSnapshot(proxyTelemetry),
                relayTelemetry = enrichRuntimeSnapshot(effectiveRelayTelemetry),
                warpTelemetry = enrichRuntimeSnapshot(effectiveWarpTelemetry),
                tunnelTelemetry = enrichRuntimeSnapshot(tunnelTelemetry),
                networkHandoverState = currentNetworkHandoverState(),
                runtimeFieldTelemetry =
                    deriveRuntimeFieldTelemetry(
                        telemetryNetworkFingerprintHash =
                            currentTelemetryFingerprintHash(currentTelemetry.runtimeFieldTelemetry),
                        winningTcpStrategyFamily = winningTcpStrategyFamily,
                        winningQuicStrategyFamily = winningQuicStrategyFamily,
                        winningDnsStrategyFamily = winningDnsStrategyFamily,
                        proxyTelemetry = enrichRuntimeSnapshot(proxyTelemetry),
                        relayTelemetry = enrichRuntimeSnapshot(effectiveRelayTelemetry),
                        warpTelemetry = enrichRuntimeSnapshot(effectiveWarpTelemetry),
                        tunnelTelemetry = enrichRuntimeSnapshot(tunnelTelemetry),
                        tunnelRecoveryRetryCount = tunnelRecoveryRetryCount,
                        failureReason = failureReason,
                    ),
                updatedAt = clock.nowMillis(),
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
        tunnelRecoveryRetryCount: Long,
        failureReason: FailureReason? = null,
    ) {
        val currentTelemetry = serviceStateStore.telemetry.value
        val enrichedTunnelTelemetry =
            applyPendingNetworkHandoverClass(
                tunnelTelemetry,
                consumePendingNetworkHandoverClass,
            )
        val (winningTcpStrategyFamily, winningQuicStrategyFamily, winningDnsStrategyFamily) =
            currentWinningFamilies(activePolicy, currentTelemetry.runtimeFieldTelemetry)

        serviceStateStore.updateTelemetry(
            ServiceTelemetrySnapshot(
                mode = mode,
                status = AppStatus.Running,
                tunnelStats = tunnelStatsFor(mode, proxyTelemetry, enrichedTunnelTelemetry),
                proxyTelemetry = enrichRuntimeSnapshot(proxyTelemetry),
                relayTelemetry = enrichRuntimeSnapshot(relayTelemetry),
                warpTelemetry = enrichRuntimeSnapshot(warpTelemetry),
                tunnelTelemetry = enrichRuntimeSnapshot(enrichedTunnelTelemetry),
                networkHandoverState = currentNetworkHandoverState(),
                runtimeFieldTelemetry =
                    deriveRuntimeFieldTelemetry(
                        telemetryNetworkFingerprintHash =
                            currentTelemetryFingerprintHash(currentTelemetry.runtimeFieldTelemetry),
                        winningTcpStrategyFamily = winningTcpStrategyFamily,
                        winningQuicStrategyFamily = winningQuicStrategyFamily,
                        winningDnsStrategyFamily = winningDnsStrategyFamily,
                        proxyTelemetry = enrichRuntimeSnapshot(proxyTelemetry),
                        relayTelemetry = enrichRuntimeSnapshot(relayTelemetry),
                        warpTelemetry = enrichRuntimeSnapshot(warpTelemetry),
                        tunnelTelemetry = enrichRuntimeSnapshot(enrichedTunnelTelemetry),
                        tunnelRecoveryRetryCount = tunnelRecoveryRetryCount,
                        failureReason = failureReason,
                    ),
                updatedAt =
                    maxOf(
                        clock.nowMillis(),
                        proxyTelemetry.capturedAt,
                        enrichedTunnelTelemetry.capturedAt,
                    ),
            ),
        )
    }

    private fun currentWinningFamilies(
        activePolicy: ActiveConnectionPolicy?,
        fallback: RuntimeFieldTelemetry,
    ): Triple<String?, String?, String?> {
        val policy = activePolicy?.policy
        return if (policy != null) {
            Triple(
                policy.winningTcpStrategyFamily,
                policy.winningQuicStrategyFamily,
                policy.winningDnsStrategyFamily,
            )
        } else {
            Triple(
                fallback.winningTcpStrategyFamily,
                fallback.winningQuicStrategyFamily,
                fallback.winningDnsStrategyFamily,
            )
        }
    }

    private fun statusSnapshot(
        newStatus: ServiceStatus,
        source: String,
        current: NativeRuntimeSnapshot,
    ): NativeRuntimeSnapshot =
        when (newStatus) {
            ServiceStatus.Connected,
            ServiceStatus.Disconnected,
            -> NativeRuntimeSnapshot.idle(source = source)

            ServiceStatus.Failed -> current
        }

    private fun currentTelemetryFingerprintHash(fallback: RuntimeFieldTelemetry): String? =
        telemetryFingerprintHasher.hash(networkFingerprintProvider.capture())
            ?: fallback.telemetryNetworkFingerprintHash

    private fun applyPendingNetworkHandoverClass(
        snapshot: NativeRuntimeSnapshot,
        consumePendingNetworkHandoverClass: () -> String?,
    ): NativeRuntimeSnapshot {
        val classification = consumePendingNetworkHandoverClass() ?: return snapshot
        return snapshot.copy(networkHandoverClass = classification)
    }

    private fun enrichRuntimeSnapshot(snapshot: NativeRuntimeSnapshot): NativeRuntimeSnapshot {
        val selection = runtimeExperimentSelectionProvider.current()
        return snapshot.copy(
            strategyPackId = snapshot.strategyPackId ?: selection.strategyPackId,
            strategyPackVersion = snapshot.strategyPackVersion ?: selection.strategyPackVersion,
            tlsProfileId = snapshot.tlsProfileId ?: selection.tlsProfileId,
            tlsProfileCatalogVersion = snapshot.tlsProfileCatalogVersion ?: selection.tlsProfileCatalogVersion,
            morphPolicyId = snapshot.morphPolicyId ?: selection.morphPolicyId,
            quicMigrationStatus = snapshot.quicMigrationStatus ?: com.poyka.ripdpi.data.QuicMigrationStatusNotAttempted,
        )
    }

    private fun tunnelStatsFor(
        mode: Mode,
        proxyTelemetry: NativeRuntimeSnapshot,
        tunnelTelemetry: NativeRuntimeSnapshot,
    ): TunnelStats =
        if (mode == Mode.Proxy) {
            proxyTelemetry.tunnelStats
        } else {
            tunnelTelemetry.tunnelStats
        }
}
