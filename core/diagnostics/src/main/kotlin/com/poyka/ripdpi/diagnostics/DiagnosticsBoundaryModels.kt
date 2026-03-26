package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprintSummary
import com.poyka.ripdpi.data.RememberedNetworkPolicyJson
import com.poyka.ripdpi.data.VpnDnsPolicyJson
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsProfileProjection
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class DiagnosticProfile(
    val id: String,
    val name: String,
    val source: String,
    val version: Int,
    val request: DiagnosticsProfileProjection? = null,
    val updatedAt: Long,
) {
    constructor(
        id: String,
        name: String,
        source: String,
        version: Int,
        requestJson: String,
        updatedAt: Long,
    ) : this(
        id = id,
        name = name,
        source = source,
        version = version,
        request = compatibilityJson.decodeProfileSpecWireCompat(requestJson).toProfileProjection(),
        updatedAt = updatedAt,
    )
}

@Serializable
data class DiagnosticScanSession(
    val id: String,
    val profileId: String,
    val approachProfileId: String? = null,
    val approachProfileName: String? = null,
    val strategyId: String? = null,
    val strategyLabel: String? = null,
    val strategySignature: BypassStrategySignature? = null,
    val pathMode: String,
    val serviceMode: String?,
    val status: String,
    val summary: String,
    val report: DiagnosticsSessionProjection? = null,
    val startedAt: Long,
    val finishedAt: Long?,
) {
    constructor(
        id: String,
        profileId: String,
        approachProfileId: String? = null,
        approachProfileName: String? = null,
        strategyId: String? = null,
        strategyLabel: String? = null,
        strategyJson: String? = null,
        pathMode: String,
        serviceMode: String?,
        status: String,
        summary: String,
        reportJson: String?,
        startedAt: Long,
        finishedAt: Long?,
    ) : this(
        id = id,
        profileId = profileId,
        approachProfileId = approachProfileId,
        approachProfileName = approachProfileName,
        strategyId = strategyId,
        strategyLabel = strategyLabel,
        strategySignature = compatibilityJson.decodeOrNull(BypassStrategySignature.serializer(), strategyJson),
        pathMode = pathMode,
        serviceMode = serviceMode,
        status = status,
        summary = summary,
        report =
            reportJson
                ?.takeIf { it.isNotBlank() }
                ?.let(compatibilityJson::decodeEngineScanReportWireCompat)
                ?.toSessionProjection(),
        startedAt = startedAt,
        finishedAt = finishedAt,
    )
}

@Serializable
data class DiagnosticNetworkSnapshot(
    val id: String,
    val sessionId: String?,
    val connectionSessionId: String? = null,
    val snapshotKind: String,
    val snapshot: NetworkSnapshotModel? = null,
    val capturedAt: Long,
) {
    constructor(
        id: String,
        sessionId: String?,
        connectionSessionId: String? = null,
        snapshotKind: String,
        payloadJson: String,
        capturedAt: Long,
    ) : this(
        id = id,
        sessionId = sessionId,
        connectionSessionId = connectionSessionId,
        snapshotKind = snapshotKind,
        snapshot = compatibilityJson.decodeOrNull(NetworkSnapshotModel.serializer(), payloadJson),
        capturedAt = capturedAt,
    )
}

@Serializable
data class DiagnosticContextSnapshot(
    val id: String,
    val sessionId: String?,
    val connectionSessionId: String? = null,
    val contextKind: String,
    val context: DiagnosticContextModel? = null,
    val capturedAt: Long,
) {
    constructor(
        id: String,
        sessionId: String?,
        connectionSessionId: String? = null,
        contextKind: String,
        payloadJson: String,
        capturedAt: Long,
    ) : this(
        id = id,
        sessionId = sessionId,
        connectionSessionId = connectionSessionId,
        contextKind = contextKind,
        context = compatibilityJson.decodeOrNull(DiagnosticContextModel.serializer(), payloadJson),
        capturedAt = capturedAt,
    )
}

@Serializable
data class DiagnosticTelemetrySample(
    val id: String,
    val sessionId: String?,
    val connectionSessionId: String? = null,
    val activeMode: String?,
    val connectionState: String,
    val networkType: String,
    val publicIp: String?,
    val failureClass: String? = null,
    val telemetryNetworkFingerprintHash: String? = null,
    val winningTcpStrategyFamily: String? = null,
    val winningQuicStrategyFamily: String? = null,
    val proxyRttBand: String = "unknown",
    val resolverRttBand: String = "unknown",
    val proxyRouteRetryCount: Long = 0,
    val tunnelRecoveryRetryCount: Long = 0,
    val resolverId: String? = null,
    val resolverProtocol: String? = null,
    val resolverEndpoint: String? = null,
    val resolverLatencyMs: Long? = null,
    val dnsFailuresTotal: Long = 0,
    val resolverFallbackActive: Boolean = false,
    val resolverFallbackReason: String? = null,
    val networkHandoverClass: String? = null,
    val lastFailureClass: String? = null,
    val lastFallbackAction: String? = null,
    val txPackets: Long,
    val txBytes: Long,
    val rxPackets: Long,
    val rxBytes: Long,
    val createdAt: Long,
)

@Serializable
data class DiagnosticEvent(
    val id: String,
    val sessionId: String?,
    val connectionSessionId: String? = null,
    val source: String,
    val level: String,
    val message: String,
    val createdAt: Long,
    val runtimeId: String? = null,
    val mode: String? = null,
    val policySignature: String? = null,
    val fingerprintHash: String? = null,
    val subsystem: String? = null,
)

@Serializable
data class DiagnosticExportRecord(
    val id: String,
    val sessionId: String?,
    val uri: String,
    val fileName: String,
    val createdAt: Long,
)

@Serializable
data class DiagnosticConnectionSession(
    val id: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val updatedAt: Long = 0L,
    val serviceMode: String,
    val connectionState: String = "Running",
    val health: String = "idle",
    val approachProfileId: String?,
    val approachProfileName: String?,
    val strategyId: String,
    val strategyLabel: String,
    val strategySignature: BypassStrategySignature? = null,
    val networkType: String,
    val publicIp: String? = null,
    val failureClass: String? = null,
    val telemetryNetworkFingerprintHash: String? = null,
    val winningTcpStrategyFamily: String? = null,
    val winningQuicStrategyFamily: String? = null,
    val proxyRttBand: String = "unknown",
    val resolverRttBand: String = "unknown",
    val proxyRouteRetryCount: Long = 0,
    val tunnelRecoveryRetryCount: Long = 0,
    val txBytes: Long,
    val rxBytes: Long,
    val totalErrors: Long,
    val routeChanges: Long,
    val restartCount: Int,
    val endedReason: String?,
    val failureMessage: String? = null,
) {
    constructor(
        id: String,
        startedAt: Long,
        finishedAt: Long?,
        updatedAt: Long = 0L,
        serviceMode: String,
        connectionState: String = "Running",
        health: String = "idle",
        approachProfileId: String?,
        approachProfileName: String?,
        strategyId: String,
        strategyLabel: String,
        strategyJson: String,
        networkType: String,
        publicIp: String? = null,
        failureClass: String? = null,
        telemetryNetworkFingerprintHash: String? = null,
        winningTcpStrategyFamily: String? = null,
        winningQuicStrategyFamily: String? = null,
        proxyRttBand: String = "unknown",
        resolverRttBand: String = "unknown",
        proxyRouteRetryCount: Long = 0,
        tunnelRecoveryRetryCount: Long = 0,
        txBytes: Long,
        rxBytes: Long,
        totalErrors: Long,
        routeChanges: Long,
        restartCount: Int,
        endedReason: String?,
        failureMessage: String? = null,
    ) : this(
        id = id,
        startedAt = startedAt,
        finishedAt = finishedAt,
        updatedAt = updatedAt,
        serviceMode = serviceMode,
        connectionState = connectionState,
        health = health,
        approachProfileId = approachProfileId,
        approachProfileName = approachProfileName,
        strategyId = strategyId,
        strategyLabel = strategyLabel,
        strategySignature = compatibilityJson.decodeOrNull(BypassStrategySignature.serializer(), strategyJson),
        networkType = networkType,
        publicIp = publicIp,
        failureClass = failureClass,
        telemetryNetworkFingerprintHash = telemetryNetworkFingerprintHash,
        winningTcpStrategyFamily = winningTcpStrategyFamily,
        winningQuicStrategyFamily = winningQuicStrategyFamily,
        proxyRttBand = proxyRttBand,
        resolverRttBand = resolverRttBand,
        proxyRouteRetryCount = proxyRouteRetryCount,
        tunnelRecoveryRetryCount = tunnelRecoveryRetryCount,
        txBytes = txBytes,
        rxBytes = rxBytes,
        totalErrors = totalErrors,
        routeChanges = routeChanges,
        restartCount = restartCount,
        endedReason = endedReason,
        failureMessage = failureMessage,
    )
}

@Serializable
data class DiagnosticsRememberedPolicy(
    val id: Long = 0L,
    val fingerprintHash: String,
    val mode: String,
    val summary: NetworkFingerprintSummary? = null,
    val proxyConfigJson: String,
    val vpnDnsPolicy: VpnDnsPolicyJson? = null,
    val strategySignature: BypassStrategySignature? = null,
    val winningTcpStrategyFamily: String? = null,
    val winningQuicStrategyFamily: String? = null,
    val source: String,
    val status: String,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val consecutiveFailureCount: Int = 0,
    val firstObservedAt: Long,
    val lastValidatedAt: Long? = null,
    val lastAppliedAt: Long? = null,
    val suppressedUntil: Long? = null,
    val updatedAt: Long,
)

data class DiagnosticActiveConnectionPolicy(
    val mode: Mode,
    val policy: RememberedNetworkPolicyJson,
    val matchedPolicy: DiagnosticsRememberedPolicy? = null,
    val usedRememberedPolicy: Boolean = false,
    val fingerprintHash: String? = null,
    val policySignature: String,
    val appliedAt: Long,
    val restartReason: String = "initial_start",
    val handoverClassification: String? = null,
)

data class DiagnosticConnectionDetail(
    val session: DiagnosticConnectionSession,
    val snapshots: List<DiagnosticNetworkSnapshot>,
    val contexts: List<DiagnosticContextSnapshot>,
    val telemetry: List<DiagnosticTelemetrySample>,
    val events: List<DiagnosticEvent>,
)

private val compatibilityJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

private fun <T> Json.decodeOrNull(
    serializer: kotlinx.serialization.KSerializer<T>,
    payload: String?,
): T? =
    payload?.takeIf { it.isNotBlank() }?.let {
        runCatching { decodeFromString(serializer, it) }.getOrNull()
    }

private val rttBandPriority =
    mapOf(
        "lt50" to 0,
        "50_99" to 1,
        "100_249" to 2,
        "250_499" to 3,
        "500_plus" to 4,
        "unknown" to -1,
    )

fun aggregateWinningStrategyFamily(
    winningTcpStrategyFamily: String?,
    winningQuicStrategyFamily: String?,
): String? =
    listOfNotNull(
        winningTcpStrategyFamily?.trim()?.takeIf { it.isNotEmpty() },
        winningQuicStrategyFamily?.trim()?.takeIf { it.isNotEmpty() },
    ).distinct().takeIf { it.isNotEmpty() }?.joinToString(" + ")

fun aggregateRttBand(
    proxyRttBand: String?,
    resolverRttBand: String?,
): String =
    listOfNotNull(proxyRttBand, resolverRttBand)
        .map { it.trim().ifEmpty { "unknown" } }
        .filterNot { it == "unknown" }
        .maxByOrNull { rttBandPriority[it] ?: -1 }
        ?: "unknown"

fun aggregateRetryCount(
    proxyRouteRetryCount: Long,
    tunnelRecoveryRetryCount: Long,
): Long = proxyRouteRetryCount + tunnelRecoveryRetryCount

fun DiagnosticTelemetrySample.winningStrategyFamily(): String? =
    aggregateWinningStrategyFamily(winningTcpStrategyFamily, winningQuicStrategyFamily)

fun DiagnosticTelemetrySample.rttBand(): String = aggregateRttBand(proxyRttBand, resolverRttBand)

fun DiagnosticTelemetrySample.retryCount(): Long = aggregateRetryCount(proxyRouteRetryCount, tunnelRecoveryRetryCount)

fun DiagnosticConnectionSession.winningStrategyFamily(): String? =
    aggregateWinningStrategyFamily(winningTcpStrategyFamily, winningQuicStrategyFamily)

fun DiagnosticConnectionSession.rttBand(): String = aggregateRttBand(proxyRttBand, resolverRttBand)

fun DiagnosticConnectionSession.retryCount(): Long = aggregateRetryCount(proxyRouteRetryCount, tunnelRecoveryRetryCount)
