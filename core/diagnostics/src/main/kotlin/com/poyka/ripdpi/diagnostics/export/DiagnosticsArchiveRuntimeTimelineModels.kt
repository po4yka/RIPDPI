package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.ConnectivityAssessment
import com.poyka.ripdpi.diagnostics.NetworkPathSnapshotPair
import com.poyka.ripdpi.diagnostics.NetworkPathValidationEvidence
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
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val failurePathProvenance: List<DiagnosticsArchiveFailurePathProvenanceEntry> = emptyList(),
)

@Serializable
internal data class DiagnosticsArchiveFailurePathProvenanceEntry(
    val failureTimestamp: Long,
    val failureClass: String? = null,
    val activeMode: String? = null,
    val networkType: String,
    val resolverId: String? = null,
    val resolverProtocol: String? = null,
    val resolverLatencyMs: Long? = null,
    val networkHandoverClass: String? = null,
    val networkHandoverState: String? = null,
    val snapshotCorrelation: String,
    val snapshotCapturedAt: Long? = null,
    val networkPath: DiagnosticsArchiveFailureNetworkPath? = null,
)

@Serializable
internal data class DiagnosticsArchiveFailureNetworkPath(
    val transport: String,
    val networkValidated: Boolean,
    val captivePortalDetected: Boolean,
    val mtuBand: String,
    val privateDnsMode: String,
    val pathValidation: NetworkPathValidationEvidence? = null,
    val pathSnapshots: NetworkPathSnapshotPair? = null,
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
