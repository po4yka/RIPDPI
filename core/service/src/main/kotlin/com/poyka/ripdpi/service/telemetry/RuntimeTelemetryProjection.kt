package com.poyka.ripdpi.service.telemetry

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NativeRuntimeSnapshot
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.RuntimeFieldTelemetry
import com.poyka.ripdpi.data.RuntimeTelemetryStatus
import com.poyka.ripdpi.data.ServiceStatus
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.data.deriveRuntimeFieldTelemetry
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicy
import com.poyka.ripdpi.data.xray.XrayProviderSnapshot
import com.poyka.ripdpi.services.RuntimeExperimentSelectionProvider
import com.poyka.ripdpi.services.ServiceClock
import com.poyka.ripdpi.services.TelemetryFingerprintHasher

internal class RuntimeTelemetryProjection(
    private val mode: Mode,
    private val networkFingerprintProvider: NetworkFingerprintProvider,
    private val telemetryFingerprintHasher: TelemetryFingerprintHasher,
    private val runtimeExperimentSelectionProvider: RuntimeExperimentSelectionProvider,
    private val clock: ServiceClock,
) {
    private companion object {
        private const val TerminalNativeEventCap = 16
    }

    fun statusTelemetry(
        newStatus: ServiceStatus,
        currentTelemetry: ServiceTelemetrySnapshot,
        activePolicy: ActiveConnectionPolicy?,
        consumePendingNetworkHandoverClass: () -> String?,
        currentNetworkHandoverState: () -> String?,
        tunnelRecoveryRetryCount: Long,
        relayTelemetry: NativeRuntimeSnapshot?,
        warpTelemetry: NativeRuntimeSnapshot?,
        awgTelemetry: NativeRuntimeSnapshot?,
        telemetryStatuses: RuntimeTelemetryStatuses = RuntimeTelemetryStatuses(),
        failureReason: FailureReason?,
        xrayProviderSnapshot: com.poyka.ripdpi.data.xray.XrayProviderSnapshot?,
    ): ServiceTelemetrySnapshot {
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
        val effectiveAwgTelemetry =
            awgTelemetry
                ?: statusSnapshot(newStatus, source = "awg", currentTelemetry.awgTelemetry)
        val (winningTcpStrategyFamily, winningQuicStrategyFamily, winningDnsStrategyFamily) =
            currentWinningFamilies(activePolicy, currentTelemetry.runtimeFieldTelemetry)

        return ServiceTelemetrySnapshot(
            mode = mode,
            status = statusFor(newStatus),
            tunnelStats = tunnelStatsFor(proxyTelemetry, tunnelTelemetry),
            proxyTelemetry = enrichRuntimeSnapshot(proxyTelemetry),
            proxyTelemetryStatus = proxyTelemetryStatusFor(newStatus, currentTelemetry, telemetryStatuses.proxy),
            relayTelemetry = enrichRuntimeSnapshot(effectiveRelayTelemetry),
            relayTelemetryStatus =
                telemetryStatusFor(
                    newStatus,
                    currentTelemetry.relayTelemetryStatus,
                    telemetryStatuses.relay,
                ),
            warpTelemetry = enrichRuntimeSnapshot(effectiveWarpTelemetry),
            warpTelemetryStatus =
                telemetryStatusFor(
                    newStatus,
                    currentTelemetry.warpTelemetryStatus,
                    telemetryStatuses.warp,
                ),
            awgTelemetry = enrichRuntimeSnapshot(effectiveAwgTelemetry),
            awgTelemetryStatus =
                telemetryStatusFor(
                    newStatus,
                    currentTelemetry.awgTelemetryStatus,
                    telemetryStatuses.awg,
                ),
            tunnelTelemetry = enrichRuntimeSnapshot(tunnelTelemetry),
            tunnelTelemetryStatus =
                telemetryStatusFor(
                    newStatus,
                    currentTelemetry.tunnelTelemetryStatus,
                    telemetryStatuses.tunnel,
                ),
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
            initialTransportRaceSnapshot = currentTelemetry.initialTransportRaceSnapshot,
            xrayProviderSnapshot = xrayProviderSnapshot ?: currentTelemetry.xrayProviderSnapshot,
            updatedAt = clock.nowMillis(),
        )
    }

    @Suppress("LongParameterList")
    fun liveTelemetry(
        currentTelemetry: ServiceTelemetrySnapshot,
        activePolicy: ActiveConnectionPolicy?,
        consumePendingNetworkHandoverClass: () -> String?,
        currentNetworkHandoverState: () -> String?,
        proxyTelemetry: NativeRuntimeSnapshot,
        relayTelemetry: NativeRuntimeSnapshot,
        warpTelemetry: NativeRuntimeSnapshot,
        awgTelemetry: NativeRuntimeSnapshot,
        tunnelTelemetry: NativeRuntimeSnapshot,
        proxyTelemetryStatus: RuntimeTelemetryStatus,
        relayTelemetryStatus: RuntimeTelemetryStatus,
        warpTelemetryStatus: RuntimeTelemetryStatus,
        awgTelemetryStatus: RuntimeTelemetryStatus,
        tunnelTelemetryStatus: RuntimeTelemetryStatus,
        tunnelRecoveryRetryCount: Long,
        failureReason: FailureReason?,
        xrayProviderSnapshot: XrayProviderSnapshot?,
    ): ServiceTelemetrySnapshot {
        val enrichedTunnelTelemetry =
            applyPendingNetworkHandoverClass(
                tunnelTelemetry,
                consumePendingNetworkHandoverClass,
            )
        val (winningTcpStrategyFamily, winningQuicStrategyFamily, winningDnsStrategyFamily) =
            currentWinningFamilies(activePolicy, currentTelemetry.runtimeFieldTelemetry)

        return ServiceTelemetrySnapshot(
            mode = mode,
            status = AppStatus.Running,
            tunnelStats = tunnelStatsFor(proxyTelemetry, enrichedTunnelTelemetry),
            proxyTelemetry = enrichRuntimeSnapshot(proxyTelemetry),
            proxyTelemetryStatus = proxyTelemetryStatus,
            relayTelemetry = enrichRuntimeSnapshot(relayTelemetry),
            relayTelemetryStatus = relayTelemetryStatus,
            warpTelemetry = enrichRuntimeSnapshot(warpTelemetry),
            warpTelemetryStatus = warpTelemetryStatus,
            awgTelemetry = enrichRuntimeSnapshot(awgTelemetry),
            awgTelemetryStatus = awgTelemetryStatus,
            tunnelTelemetry = enrichRuntimeSnapshot(enrichedTunnelTelemetry),
            tunnelTelemetryStatus = tunnelTelemetryStatus,
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
            initialTransportRaceSnapshot = currentTelemetry.initialTransportRaceSnapshot,
            xrayProviderSnapshot = xrayProviderSnapshot ?: currentTelemetry.xrayProviderSnapshot,
            updatedAt =
                maxOf(
                    clock.nowMillis(),
                    proxyTelemetry.capturedAt,
                    enrichedTunnelTelemetry.capturedAt,
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
            ServiceStatus.Connected -> {
                current
            }

            ServiceStatus.Disconnected -> {
                NativeRuntimeSnapshot.idle(source = source).copy(
                    nativeEvents =
                        current.nativeEvents
                            .filter { it.subsystem == "data_plane" }
                            .takeLast(TerminalNativeEventCap),
                )
            }

            ServiceStatus.Failed -> {
                current
            }
        }

    private fun currentTelemetryFingerprintHash(fallback: RuntimeFieldTelemetry): String? =
        telemetryFingerprintHasher.hash(networkFingerprintProvider.capture())
            ?: fallback.telemetryNetworkFingerprintHash

    private fun proxyTelemetryStatusFor(
        newStatus: ServiceStatus,
        currentTelemetry: ServiceTelemetrySnapshot,
        reportedStatus: RuntimeTelemetryStatus?,
    ): RuntimeTelemetryStatus = telemetryStatusFor(newStatus, currentTelemetry.proxyTelemetryStatus, reportedStatus)

    private fun telemetryStatusFor(
        newStatus: ServiceStatus,
        currentStatus: RuntimeTelemetryStatus,
        reportedStatus: RuntimeTelemetryStatus?,
    ): RuntimeTelemetryStatus =
        when (newStatus) {
            ServiceStatus.Failed -> reportedStatus ?: currentStatus
            ServiceStatus.Connected -> reportedStatus ?: currentStatus
            ServiceStatus.Disconnected -> reportedStatus ?: RuntimeTelemetryStatus.NoData
        }

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
        proxyTelemetry: NativeRuntimeSnapshot,
        tunnelTelemetry: NativeRuntimeSnapshot,
    ): TunnelStats =
        if (mode == Mode.Proxy) {
            proxyTelemetry.tunnelStats
        } else {
            tunnelTelemetry.tunnelStats
        }

    private fun statusFor(newStatus: ServiceStatus): AppStatus =
        when (newStatus) {
            ServiceStatus.Connected -> AppStatus.Running

            ServiceStatus.Failed,
            ServiceStatus.Disconnected,
            -> AppStatus.Halted
        }
}
