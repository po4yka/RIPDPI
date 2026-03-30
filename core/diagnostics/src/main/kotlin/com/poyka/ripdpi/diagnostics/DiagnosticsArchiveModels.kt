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
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class DiagnosticsArchiveRequest(
    val requestedSessionId: String? = null,
    val reason: DiagnosticsArchiveReason,
    val requestedAt: Long,
)

@Serializable
enum class DiagnosticsArchiveReason {
    SHARE_ARCHIVE,
    SAVE_ARCHIVE,
    SHARE_DEBUG_BUNDLE,
}

internal object DiagnosticsArchiveFormat {
    const val directoryName = "diagnostics-archives"
    const val fileNamePrefix = "ripdpi-diagnostics-"
    const val schemaVersion = 2
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
            add("archive-provenance.json")
            add("runtime-config.json")
            add("analysis.json")
            add("completeness.json")
            add("native-events.csv")
            add("telemetry.csv")
            add("network-snapshots.json")
            add("diagnostic-context.json")
            if (logcatIncluded) {
                add("logcat.txt")
            }
            add("integrity.json")
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
    val appSettings: AppSettings,
    val buildProvenance: DiagnosticsArchiveBuildProvenance,
    val collectionWarnings: List<String>,
    val logcatSnapshot: LogcatSnapshot?,
)

internal data class DiagnosticsArchiveSelection(
    val request: DiagnosticsArchiveRequest,
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
    val buildProvenance: DiagnosticsArchiveBuildProvenance,
    val sessionSelectionStatus: DiagnosticsArchiveSessionSelectionStatus,
    val effectiveStrategySignature: BypassStrategySignature?,
    val appSettings: AppSettings,
    val sourceCounts: DiagnosticsArchiveSourceCounts,
    val collectionWarnings: List<String>,
    val includedFiles: List<String>,
    val logcatSnapshot: LogcatSnapshot?,
)

@Serializable
internal enum class DiagnosticsArchiveSessionSelectionStatus {
    @SerialName("requested_session")
    REQUESTED_SESSION,

    @SerialName("latest_completed_session")
    LATEST_COMPLETED_SESSION,

    @SerialName("latest_live_state")
    LATEST_LIVE_STATE,

    @SerialName("support_bundle")
    SUPPORT_BUNDLE,
}

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
internal data class DiagnosticsArchiveNativeLibraryProvenance(
    val name: String,
    val version: String,
)

@Serializable
internal data class DiagnosticsArchiveBuildProvenance(
    val applicationId: String,
    val appVersionName: String,
    val appVersionCode: Long,
    val buildType: String,
    val gitCommit: String,
    val nativeLibraries: List<DiagnosticsArchiveNativeLibraryProvenance>,
)

@Serializable
internal data class DiagnosticsArchiveBuildProvenanceSummary(
    val applicationId: String,
    val appVersionName: String,
    val appVersionCode: Long,
    val buildType: String,
    val gitCommit: String,
    val nativeLibraries: List<String>,
)

@Serializable
internal data class DiagnosticsArchiveRuntimeProvenance(
    val runtimeId: String? = null,
    val mode: String? = null,
    val policySignature: String? = null,
    val fingerprintHash: String? = null,
    val networkScope: String? = null,
    val androidVersion: String? = null,
    val apiLevel: Int? = null,
    val primaryAbi: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
)

@Serializable
internal data class DiagnosticsArchiveTriggerMetadata(
    val launchOrigin: String? = null,
    val triggerType: String? = null,
    val triggerClassification: String? = null,
    val triggerOccurredAt: Long? = null,
)

@Serializable
internal data class DiagnosticsArchiveProvenancePayload(
    val archiveReason: DiagnosticsArchiveReason,
    val requestedAt: Long,
    val createdAt: Long,
    val requestedSessionId: String? = null,
    val selectedSessionId: String? = null,
    val sessionSelectionStatus: DiagnosticsArchiveSessionSelectionStatus,
    val triggerMetadata: DiagnosticsArchiveTriggerMetadata? = null,
    val buildProvenance: DiagnosticsArchiveBuildProvenance,
    val runtimeProvenance: DiagnosticsArchiveRuntimeProvenance,
)

@Serializable
internal data class DiagnosticsArchiveRuntimeConfigPayload(
    val configuredMode: String = "unavailable",
    val activeMode: String = "unavailable",
    val serviceStatus: String = "unavailable",
    val selectedProfileId: String = "unavailable",
    val selectedProfileName: String = "unavailable",
    val configSource: String = "unavailable",
    val desyncMethod: String = "unavailable",
    val chainSummary: String = "unavailable",
    val routeGroup: String = "unavailable",
    val restartCount: Int = 0,
    val sessionUptimeMs: Long? = null,
    val hostAutolearnEnabled: String = "unavailable",
    val learnedHostCount: Int = 0,
    val penalizedHostCount: Int = 0,
    val blockedHostCount: Int = 0,
    val lastBlockSignal: String = "unavailable",
    val lastBlockProvider: String = "unavailable",
    val lastAutolearnHost: String = "unavailable",
    val lastAutolearnGroup: String = "unavailable",
    val lastAutolearnAction: String = "unavailable",
    val lastNativeErrorHeadline: String = "unavailable",
    val resolverId: String = "unavailable",
    val resolverProtocol: String = "unavailable",
    val resolverEndpoint: String = "unavailable",
    val resolverLatencyMs: Long? = null,
    val resolverFallbackActive: Boolean = false,
    val resolverFallbackReason: String = "unavailable",
    val networkHandoverClass: String = "unavailable",
    val transport: String = "unavailable",
    val privateDnsMode: String = "unavailable",
    val mtu: Int? = null,
    val networkValidated: Boolean? = null,
    val captivePortalDetected: Boolean? = null,
    val batterySaverState: String = "unavailable",
    val powerSaveModeState: String = "unavailable",
    val dataSaverState: String = "unavailable",
    val batteryOptimizationState: String = "unavailable",
    val vpnPermissionState: String = "unavailable",
    val notificationPermissionState: String = "unavailable",
    val networkMeteredState: String = "unavailable",
    val roamingState: String = "unavailable",
    val commandLineSettingsEnabled: Boolean = false,
    val commandLineArgsHash: String? = null,
    val effectiveStrategySignature: BypassStrategySignature? = null,
)

@Serializable
internal data class DiagnosticsArchiveRetryCounters(
    val proxyRouteRetryCount: Long = 0,
    val tunnelRecoveryRetryCount: Long = 0,
    val totalRetryCount: Long = 0,
)

@Serializable
internal data class DiagnosticsArchiveFailureEnvelope(
    val firstFailureTimestamp: Long? = null,
    val lastFailureTimestamp: Long? = null,
    val latestFailureClass: String? = null,
    val lastFallbackAction: String? = null,
    val retryCounters: DiagnosticsArchiveRetryCounters = DiagnosticsArchiveRetryCounters(),
    val failureClassTransitions: List<String> = emptyList(),
)

@Serializable
internal data class DiagnosticsArchiveCandidateFactBreakdown(
    val observationCount: Int = 0,
    val statusCounts: Map<String, Int> = emptyMap(),
    val transportFailureCounts: Map<String, Int> = emptyMap(),
    val tlsErrorSamples: List<String> = emptyList(),
)

@Serializable
internal data class DiagnosticsArchiveCandidateExecutionDetail(
    val lane: String,
    val id: String,
    val label: String,
    val family: String,
    val outcome: String,
    val rationale: String,
    val succeededTargets: Int,
    val totalTargets: Int,
    val weightedSuccessScore: Int,
    val totalWeight: Int,
    val qualityScore: Int,
    val averageLatencyMs: Long? = null,
    val skipped: Boolean = false,
    val skipReasons: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val factBreakdown: DiagnosticsArchiveCandidateFactBreakdown = DiagnosticsArchiveCandidateFactBreakdown(),
)

@Serializable
internal data class DiagnosticsArchiveRecommendationTrace(
    val selectedApproach: String,
    val selectedStrategy: String,
    val selectedResolver: String? = null,
    val confidenceLevel: String? = null,
    val confidenceScore: Int? = null,
    val coveragePercent: Int? = null,
    val winnerCoveragePercent: Int? = null,
    val targetCohort: String? = null,
    val evidence: List<String> = emptyList(),
)

@Serializable
internal data class DiagnosticsArchiveStrategyExecutionDetail(
    val suiteId: String? = null,
    val completionKind: String? = null,
    val tcpCandidates: List<DiagnosticsArchiveCandidateExecutionDetail> = emptyList(),
    val quicCandidates: List<DiagnosticsArchiveCandidateExecutionDetail> = emptyList(),
)

@Serializable
internal data class DiagnosticsArchiveAnalysisPayload(
    val failureEnvelope: DiagnosticsArchiveFailureEnvelope,
    val strategyExecutionDetail: DiagnosticsArchiveStrategyExecutionDetail,
    val recommendationTrace: DiagnosticsArchiveRecommendationTrace? = null,
)

@Serializable
internal enum class DiagnosticsArchiveSectionStatus {
    @SerialName("included")
    INCLUDED,

    @SerialName("redacted")
    REDACTED,

    @SerialName("omitted")
    OMITTED,

    @SerialName("unavailable")
    UNAVAILABLE,

    @SerialName("truncated")
    TRUNCATED,
}

@Serializable
internal data class DiagnosticsArchiveAppliedLimits(
    val telemetrySamples: Int,
    val nativeEvents: Int,
    val snapshots: Int,
    val logcatBytes: Int,
)

@Serializable
internal data class DiagnosticsArchiveSourceCounts(
    val telemetrySamples: Int,
    val nativeEvents: Int,
    val snapshots: Int,
    val contexts: Int,
    val sessionResults: Int,
    val sessionSnapshots: Int,
    val sessionContexts: Int,
    val sessionEvents: Int,
)

@Serializable
internal data class DiagnosticsArchiveTruncation(
    val telemetrySamples: Boolean = false,
    val nativeEvents: Boolean = false,
    val snapshots: Boolean = false,
    val contexts: Boolean = false,
    val logcat: Boolean = false,
)

@Serializable
internal data class DiagnosticsArchiveCompletenessPayload(
    val sectionStatuses: Map<String, DiagnosticsArchiveSectionStatus>,
    val appliedLimits: DiagnosticsArchiveAppliedLimits,
    val sourceCounts: DiagnosticsArchiveSourceCounts,
    val includedCounts: DiagnosticsArchiveSourceCounts,
    val collectionWarnings: List<String> = emptyList(),
    val truncation: DiagnosticsArchiveTruncation = DiagnosticsArchiveTruncation(),
)

@Serializable
internal data class DiagnosticsArchiveIntegrityFileEntry(
    val name: String,
    val byteCount: Int,
    val sha256: String,
)

@Serializable
internal data class DiagnosticsArchiveIntegrityPayload(
    val hashAlgorithm: String,
    val schemaVersion: Int,
    val generatedAt: Long,
    val files: List<DiagnosticsArchiveIntegrityFileEntry>,
)

@Serializable
internal data class DiagnosticsArchiveManifest(
    val fileName: String,
    val createdAt: Long,
    val schemaVersion: Int,
    val privacyMode: String,
    val scope: String,
    val archiveReason: DiagnosticsArchiveReason? = null,
    val requestedSessionId: String? = null,
    val selectedSessionId: String? = null,
    val sessionSelectionStatus: DiagnosticsArchiveSessionSelectionStatus? = null,
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
    val buildProvenance: DiagnosticsArchiveBuildProvenanceSummary? = null,
    val sectionStatusSummary: Map<String, DiagnosticsArchiveSectionStatus> = emptyMap(),
    val integrityAlgorithm: String? = null,
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
