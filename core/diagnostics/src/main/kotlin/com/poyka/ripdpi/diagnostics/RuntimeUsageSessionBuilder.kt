package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeFieldTelemetry
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import kotlinx.serialization.json.Json
import java.util.Locale

internal val RuntimeHistoryJson =
    Json {
        ignoreUnknownKeys = true
    }

internal data class RuntimeSessionSeed(
    val approach: StoredApproachSnapshot,
    val restartCount: Int,
)

internal object RuntimeUsageSessionBuilder {
    fun createActiveSession(
        sessionId: String,
        mode: Mode,
        startedAt: Long,
        networkType: String,
        publicIp: String?,
        telemetry: ServiceTelemetrySnapshot,
        seed: RuntimeSessionSeed,
    ): BypassUsageSessionEntity =
        applyRuntimeFieldTelemetry(
            BypassUsageSessionEntity(
                id = sessionId,
                startedAt = startedAt,
                finishedAt = null,
                updatedAt = startedAt,
                serviceMode = mode.name,
                connectionState = AppStatus.Running.name,
                health = deriveConnectionHealth(telemetry),
                approachProfileId = seed.approach.profileId,
                approachProfileName = seed.approach.profileName,
                strategyId = seed.approach.strategyId,
                strategyLabel = seed.approach.strategyLabel,
                strategyJson = seed.approach.strategyJson,
                networkType = networkType,
                publicIp = publicIp,
                txBytes = telemetry.tunnelStats.txBytes,
                rxBytes = telemetry.tunnelStats.rxBytes,
                totalErrors = totalErrors(telemetry),
                routeChanges = routeChanges(telemetry),
                restartCount = seed.restartCount,
                endedReason = null,
                failureMessage = null,
            ),
            telemetry.runtimeFieldTelemetry,
        )

    fun createFailedSession(
        sessionId: String,
        mode: Mode,
        sender: Sender,
        failureMessage: String,
        timestamp: Long,
        networkType: String,
        publicIp: String?,
        telemetry: ServiceTelemetrySnapshot,
        seed: RuntimeSessionSeed,
    ): BypassUsageSessionEntity =
        applyRuntimeFieldTelemetry(
            BypassUsageSessionEntity(
                id = sessionId,
                startedAt = timestamp,
                finishedAt = timestamp,
                updatedAt = timestamp,
                serviceMode = mode.name,
                connectionState = "Failed",
                health = "degraded",
                approachProfileId = seed.approach.profileId,
                approachProfileName = seed.approach.profileName,
                strategyId = seed.approach.strategyId,
                strategyLabel = seed.approach.strategyLabel,
                strategyJson = seed.approach.strategyJson,
                networkType = networkType,
                publicIp = publicIp,
                txBytes = telemetry.tunnelStats.txBytes,
                rxBytes = telemetry.tunnelStats.rxBytes,
                totalErrors = totalErrors(telemetry),
                routeChanges = routeChanges(telemetry),
                restartCount = seed.restartCount,
                endedReason = "failed:${sender.senderName.lowercase(Locale.US)}",
                failureMessage = failureMessage,
            ),
            telemetry.runtimeFieldTelemetry,
        )

    fun updateRunningSession(
        current: BypassUsageSessionEntity,
        serviceMode: Mode,
        telemetry: ServiceTelemetrySnapshot,
        timestamp: Long,
        networkType: String,
        publicIp: String?,
    ): BypassUsageSessionEntity =
        applyRuntimeFieldTelemetry(
            current.copy(
                updatedAt = timestamp,
                serviceMode = serviceMode.name,
                connectionState = telemetry.status.name,
                health = deriveConnectionHealth(telemetry),
                networkType = networkType,
                publicIp = publicIp ?: current.publicIp,
                txBytes = telemetry.tunnelStats.txBytes,
                rxBytes = telemetry.tunnelStats.rxBytes,
                totalErrors = totalErrors(telemetry),
                routeChanges = routeChanges(telemetry),
                restartCount = telemetry.restartCount,
            ),
            telemetry.runtimeFieldTelemetry,
        )

    fun updateFailedSession(
        current: BypassUsageSessionEntity,
        sender: Sender,
        failureMessage: String,
        timestamp: Long,
        telemetry: ServiceTelemetrySnapshot,
        networkType: String,
        publicIp: String?,
    ): BypassUsageSessionEntity =
        applyRuntimeFieldTelemetry(
            current.copy(
                updatedAt = timestamp,
                connectionState = "Failed",
                health = "degraded",
                networkType = networkType,
                publicIp = publicIp ?: current.publicIp,
                txBytes = telemetry.tunnelStats.txBytes,
                rxBytes = telemetry.tunnelStats.rxBytes,
                totalErrors = totalErrors(telemetry),
                routeChanges = routeChanges(telemetry),
                restartCount = telemetry.restartCount,
                endedReason = "failed:${sender.senderName.lowercase(Locale.US)}",
                failureMessage = failureMessage,
            ),
            telemetry.runtimeFieldTelemetry,
        )

    fun finalizeSession(
        current: BypassUsageSessionEntity,
        telemetry: ServiceTelemetrySnapshot,
        finalizedAt: Long,
    ): BypassUsageSessionEntity =
        applyRuntimeFieldTelemetry(
            current.copy(
                finishedAt = finalizedAt,
                updatedAt = finalizedAt,
                connectionState =
                    if (current.failureMessage.isNullOrBlank()) {
                        "Stopped"
                    } else {
                        "Failed"
                    },
                health =
                    if (current.failureMessage.isNullOrBlank()) {
                        current.health
                    } else {
                        "degraded"
                    },
                txBytes = telemetry.tunnelStats.txBytes,
                rxBytes = telemetry.tunnelStats.rxBytes,
                totalErrors = totalErrors(telemetry),
                routeChanges = routeChanges(telemetry),
                restartCount = telemetry.restartCount,
                endedReason =
                    current.endedReason
                        ?: telemetry.lastFailureSender
                            ?.senderName
                            ?.lowercase(Locale.US)
                            ?.let { "failed:$it" }
                        ?: "stopped",
            ),
            telemetry.runtimeFieldTelemetry,
        )

    private fun applyRuntimeFieldTelemetry(
        session: BypassUsageSessionEntity,
        runtimeFieldTelemetry: RuntimeFieldTelemetry,
    ): BypassUsageSessionEntity =
        session.copy(
            failureClass = runtimeFieldTelemetry.failureClass?.wireValue,
            telemetryNetworkFingerprintHash = runtimeFieldTelemetry.telemetryNetworkFingerprintHash,
            winningTcpStrategyFamily = runtimeFieldTelemetry.winningTcpStrategyFamily,
            winningQuicStrategyFamily = runtimeFieldTelemetry.winningQuicStrategyFamily,
            proxyRttBand = runtimeFieldTelemetry.proxyRttBand.wireValue,
            resolverRttBand = runtimeFieldTelemetry.resolverRttBand.wireValue,
            proxyRouteRetryCount = runtimeFieldTelemetry.proxyRouteRetryCount,
            tunnelRecoveryRetryCount = runtimeFieldTelemetry.tunnelRecoveryRetryCount,
        )

    private fun deriveConnectionHealth(telemetry: ServiceTelemetrySnapshot): String {
        val healths =
            listOf(
                telemetry.proxyTelemetry.health.lowercase(Locale.US),
                telemetry.tunnelTelemetry.health.lowercase(Locale.US),
            )
        return when {
            telemetry.lastFailureSender != null -> "degraded"
            healths.any { it == "degraded" } -> "degraded"
            healths.any { it == "healthy" } -> "healthy"
            telemetry.status == AppStatus.Running -> "active"
            else -> "idle"
        }
    }

    private fun totalErrors(telemetry: ServiceTelemetrySnapshot): Long =
        telemetry.proxyTelemetry.totalErrors + telemetry.tunnelTelemetry.totalErrors

    private fun routeChanges(telemetry: ServiceTelemetrySnapshot): Long =
        telemetry.proxyTelemetry.routeChanges + telemetry.tunnelTelemetry.routeChanges
}
