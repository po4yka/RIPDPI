package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.data.diagnostics.retryCount
import com.poyka.ripdpi.data.diagnostics.rttBand
import com.poyka.ripdpi.data.diagnostics.winningStrategyFamily
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import kotlinx.serialization.Serializable
import java.io.File

internal object DiagnosticsArchiveFormat {
    const val directoryName = "diagnostics-archives"
    const val fileNamePrefix = "ripdpi-diagnostics-"
    const val schemaVersion = 9
    const val privacyMode = "split_output"
    const val scope = "hybrid"
    const val maxArchiveFiles = 5
    const val maxArchiveAgeMs = 3L * 24L * 60L * 60L * 1000L
    const val telemetryLimit = 120
    const val globalEventLimit = 80
    const val snapshotLimit = 250

    fun includedFiles(logcatIncluded: Boolean): List<String> =
        buildList {
            add("summary.txt")
            add("manifest.json")
            add("report.json")
            add("strategy-matrix.json")
            add("probe-results.csv")
            add("native-events.csv")
            add("telemetry.csv")
            add("network-snapshots.json")
            add("diagnostic-context.json")
            if (logcatIncluded) {
                add("logcat.txt")
            }
        }
}

internal data class DiagnosticsArchiveTarget(
    val file: File,
    val fileName: String,
    val createdAt: Long,
)

internal data class DiagnosticsArchiveEntry(
    val name: String,
    val bytes: ByteArray,
)

internal data class DiagnosticsArchiveSourceData(
    val sessions: List<ScanSessionEntity>,
    val usageSessions: List<BypassUsageSessionEntity>,
    val snapshots: List<NetworkSnapshotEntity>,
    val telemetry: List<TelemetrySampleEntity>,
    val events: List<NativeSessionEventEntity>,
    val contexts: List<DiagnosticContextEntity>,
    val approachSummaries: List<BypassApproachSummary>,
    val logcatSnapshot: LogcatSnapshot?,
)

internal data class DiagnosticsArchiveSelection(
    val payload: DiagnosticsArchivePayload,
    val primarySession: ScanSessionEntity?,
    val primaryReport: EngineScanReportWire?,
    val primaryResults: List<ProbeResultEntity>,
    val primarySnapshots: List<NetworkSnapshotEntity>,
    val primaryContexts: List<DiagnosticContextEntity>,
    val primaryEvents: List<NativeSessionEventEntity>,
    val latestPassiveSnapshot: NetworkSnapshotEntity?,
    val latestPassiveContext: DiagnosticContextEntity?,
    val globalEvents: List<NativeSessionEventEntity>,
    val selectedApproachSummary: BypassApproachSummary?,
    val latestSnapshotModel: NetworkSnapshotModel?,
    val latestContextModel: DiagnosticContextModel?,
    val sessionContextModel: DiagnosticContextModel?,
    val includedFiles: List<String>,
    val logcatSnapshot: LogcatSnapshot?,
)

@Serializable
internal data class DiagnosticsArchivePayload(
    val schemaVersion: Int,
    val scope: String,
    val privacyMode: String,
    val session: ScanSessionEntity?,
    val primaryReport: EngineScanReportWire? = null,
    val results: List<ProbeResultEntity>,
    val sessionSnapshots: List<NetworkSnapshotEntity>,
    val sessionContexts: List<DiagnosticContextEntity>,
    val sessionEvents: List<NativeSessionEventEntity>,
    val latestPassiveSnapshot: NetworkSnapshotEntity?,
    val latestPassiveContext: DiagnosticContextEntity?,
    val telemetry: List<TelemetrySampleEntity>,
    val globalEvents: List<NativeSessionEventEntity>,
    val approachSummaries: List<BypassApproachSummary>,
)

@Serializable
internal data class StrategyMatrixArchivePayload(
    val sessionId: String?,
    val profileId: String?,
    val strategyProbeReport: StrategyProbeReport? = null,
)

@Serializable
internal data class DiagnosticsArchiveSnapshotPayload(
    val sessionSnapshots: List<NetworkSnapshotModel>,
    val latestPassiveSnapshot: NetworkSnapshotModel?,
)

@Serializable
internal data class DiagnosticsArchiveContextPayload(
    val sessionContexts: List<DiagnosticContextModel>,
    val latestPassiveContext: DiagnosticContextModel?,
)

@Serializable
internal data class DiagnosticsArchiveManifest(
    val fileName: String,
    val createdAt: Long,
    val schemaVersion: Int,
    val privacyMode: String,
    val scope: String,
    val includedSessionId: String?,
    val sessionResultCount: Int,
    val sessionSnapshotCount: Int,
    val contextSnapshotCount: Int,
    val sessionEventCount: Int,
    val telemetrySampleCount: Int,
    val globalEventCount: Int,
    val approachCount: Int,
    val selectedApproach: BypassApproachSummary?,
    val networkSummary: RedactedNetworkSummary?,
    val contextSummary: RedactedDiagnosticContextSummary?,
    val latestTelemetrySummary: ArchiveTelemetrySummary? = null,
    val runtimeId: String? = null,
    val mode: String? = null,
    val policySignature: String? = null,
    val fingerprintHash: String? = null,
    val networkScope: String? = null,
    val lastFailure: String? = null,
    val lifecycleMilestones: List<String> = emptyList(),
    val recentNativeWarnings: List<String> = emptyList(),
    val classifierVersion: String? = null,
    val diagnosisCount: Int = 0,
    val packVersions: Map<String, Int> = emptyMap(),
    val includedFiles: List<String>,
    val logcatIncluded: Boolean,
    val logcatCaptureScope: String,
    val logcatByteCount: Int,
)

internal fun TelemetrySampleEntity.toArchiveTelemetrySummary(): ArchiveTelemetrySummary =
    ArchiveTelemetrySummary(
        failureClass = failureClass,
        lastFailureClass = lastFailureClass,
        lastFallbackAction = lastFallbackAction,
        telemetryNetworkFingerprintHash = telemetryNetworkFingerprintHash,
        winningTcpStrategyFamily = winningTcpStrategyFamily,
        winningQuicStrategyFamily = winningQuicStrategyFamily,
        winningStrategyFamily = winningStrategyFamily(),
        proxyRttBand = proxyRttBand,
        resolverRttBand = resolverRttBand,
        rttBand = rttBand(),
        proxyRouteRetryCount = proxyRouteRetryCount,
        tunnelRecoveryRetryCount = tunnelRecoveryRetryCount,
        retryCount = retryCount(),
    )

@Serializable
internal data class ArchiveTelemetrySummary(
    val failureClass: String? = null,
    val lastFailureClass: String? = null,
    val lastFallbackAction: String? = null,
    val telemetryNetworkFingerprintHash: String? = null,
    val winningTcpStrategyFamily: String? = null,
    val winningQuicStrategyFamily: String? = null,
    val winningStrategyFamily: String? = null,
    val proxyRttBand: String = "unknown",
    val resolverRttBand: String = "unknown",
    val rttBand: String = "unknown",
    val proxyRouteRetryCount: Long = 0,
    val tunnelRecoveryRetryCount: Long = 0,
    val retryCount: Long = 0,
)
