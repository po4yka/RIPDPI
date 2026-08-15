package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.ConnectivityAssessment
import com.poyka.ripdpi.diagnostics.DiagnosticsAppliedSetting
import com.poyka.ripdpi.diagnostics.HomeReproAction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

@Serializable
internal data class DiagnosticsArchiveAnalysisPayload(
    val failureEnvelope: DiagnosticsArchiveFailureEnvelope,
    val strategyExecutionDetail: DiagnosticsArchiveStrategyExecutionDetail,
    val recommendationTrace: DiagnosticsArchiveRecommendationTrace? = null,
    val measurementSnapshot: DiagnosticsArchiveMeasurementSnapshot = DiagnosticsArchiveMeasurementSnapshot(),
    val connectivityAssessment: ConnectivityAssessment? = null,
    val runtimeSnapshotTimeline: List<DiagnosticsArchiveRuntimeSnapshotTimelineEntry> = emptyList(),
)

@Serializable
internal data class DiagnosticsArchiveRuntimeSnapshotTimelineEntry(
    val snapshotRef: String,
    val connectionRef: String,
    val capturedAt: Long,
)

@Serializable
internal data class DiagnosticsArchiveRuntimeSnapshotRecord(
    val snapshotRef: String,
    val connectionRef: String,
    val capturedAt: Long,
    val snapshot: com.poyka.ripdpi.diagnostics.NetworkSnapshotModel,
)

@Serializable
internal data class DiagnosticsArchiveCompletenessPayload(
    val sectionStatuses: Map<String, DiagnosticsArchiveSectionStatus>,
    val appliedLimits: DiagnosticsArchiveAppliedLimits,
    val sourceCounts: DiagnosticsArchiveScopedCounts,
    val includedCounts: DiagnosticsArchiveScopedCounts,
    val relayAttemptTraces: DiagnosticsArchiveRelayTraceCompleteness = DiagnosticsArchiveRelayTraceCompleteness(),
    val collectionWarnings: List<String> = emptyList(),
    val reasons: List<DiagnosticsArchiveCompletenessReason> = emptyList(),
    val truncation: DiagnosticsArchiveTruncation = DiagnosticsArchiveTruncation(),
)

@Serializable
internal data class DiagnosticsArchiveCompletenessReason(
    val section: String,
    val code: String,
    val count: Int = 1,
)

@Serializable
internal data class DiagnosticsArchiveHomeAnalysisPayload(
    val runId: String,
    val fingerprintHash: String? = null,
    val actionable: Boolean,
    val headline: String,
    val summary: String,
    val recommendationSummary: String? = null,
    val confidenceSummary: String? = null,
    val coverageSummary: String? = null,
    val recommendedSessionId: String? = null,
    val appliedSettings: List<DiagnosticsAppliedSetting> = emptyList(),
    val completedStageCount: Int,
    val failedStageCount: Int,
    val skippedStageCount: Int,
    val bundleSessionIds: List<String> = emptyList(),
    val connectivityAssessment: ConnectivityAssessment? = null,
    val internetLossReproAction: HomeReproAction? = null,
    val detectionProvenance: DiagnosticsArchiveDetectionProvenance? = null,
)

@Serializable
internal data class DiagnosticsArchiveDetectionProvenance(
    val stageKey: String,
    val verdict: String? = null,
    val ruleApplied: String? = null,
    val evidenceScopes: List<String> = emptyList(),
    val evidenceStatus: String,
    val uniqueSignalCount: JsonElement = JsonNull,
    val localFindingCount: JsonElement = JsonNull,
    val networkFindingCount: JsonElement = JsonNull,
)
