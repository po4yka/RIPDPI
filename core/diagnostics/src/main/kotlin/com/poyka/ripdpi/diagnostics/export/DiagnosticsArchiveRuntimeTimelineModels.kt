package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.ConnectivityAssessment
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

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
    val source: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val capturedAt: Long? = null,
    val serviceStatus: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val proxyHealth: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tunnelHealth: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val relayHealth: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val warpHealth: String? = null,
)
