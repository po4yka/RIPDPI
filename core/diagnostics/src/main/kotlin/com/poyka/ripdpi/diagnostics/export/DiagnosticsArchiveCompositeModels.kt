package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageSummary
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal data class DiagnosticsArchiveCompositeStageSelection(
    val stageSummary: DiagnosticsHomeCompositeStageSummary,
    val session: ScanSessionEntity?,
    val report: EngineScanReportWire?,
    val results: List<ProbeResultEntity>,
    val snapshots: List<NetworkSnapshotEntity>,
    val contexts: List<DiagnosticContextEntity>,
    val events: List<NativeSessionEventEntity>,
    val telemetry: List<TelemetrySampleEntity> = emptyList(),
    val sourceSnapshotCount: Int = snapshots.size,
    val sourceContextCount: Int = contexts.size,
    val sourceEventCount: Int = events.size,
    val sourceTelemetryCount: Int = telemetry.size,
    val sourceEventIds: Set<String> = events.mapTo(linkedSetOf()) { it.id },
    val sourceTelemetryIds: Set<String> = telemetry.mapTo(linkedSetOf()) { it.id },
)

@Serializable
internal data class DiagnosticsArchiveStageIndexEntry(
    val stageKey: String,
    val stageLabel: String,
    val profileId: String,
    val pathMode: String,
    val sessionId: String? = null,
    val status: String,
    val headline: String,
    val summary: String,
    val recommendationContributor: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val evidenceSource: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val detectionVerdict: com.poyka.ripdpi.diagnostics.DiagnosticsHomeDetectionVerdict? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val detectedSignalCount: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val detectionFindings: List<String> = emptyList(),
    val sourceSnapshotCount: Int = 0,
    val includedSnapshotCount: Int = 0,
    val snapshotsTruncated: Boolean = false,
    val sourceContextCount: Int = 0,
    val includedContextCount: Int = 0,
    val contextsTruncated: Boolean = false,
    val sourceEventCount: Int = 0,
    val includedEventCount: Int = 0,
    val eventsTruncated: Boolean = false,
    val sourceTelemetryCount: Int = 0,
    val includedTelemetryCount: Int = 0,
    val telemetryTruncated: Boolean = false,
)

@Serializable
internal data class DiagnosticsArchiveStageIndexPayload(
    val runId: String,
    val stages: List<DiagnosticsArchiveStageIndexEntry>,
)

@Serializable
internal data class DiagnosticsArchiveStageSummariesPayload(
    val runId: String,
    val stages: List<DiagnosticsArchiveStageIndexEntry>,
)

@Serializable
internal data class DiagnosticsArchiveStageEvidenceCompleteness(
    val stageKey: String,
    val stageStatus: String,
    val counts: DiagnosticsArchiveStageEvidenceCounts,
)

@Serializable
internal data class DiagnosticsArchiveStageEvidenceCounts(
    val snapshots: DiagnosticsArchiveStageEvidenceCount,
    val contexts: DiagnosticsArchiveStageEvidenceCount,
    val nativeEvents: DiagnosticsArchiveStageEvidenceCount,
    val telemetrySamples: DiagnosticsArchiveStageEvidenceCount,
    val plannedAttempts: DiagnosticsArchiveStageEvidenceCount,
    val executedAttempts: DiagnosticsArchiveStageEvidenceCount,
    val observations: DiagnosticsArchiveStageEvidenceCount,
    val diagnoses: DiagnosticsArchiveStageEvidenceCount,
    val verdictEvidenceRefs: DiagnosticsArchiveStageEvidenceCount,
)

@Serializable
internal data class DiagnosticsArchiveStageEvidenceCount(
    val sourceCount: Int? = null,
    val includedCount: Int? = null,
    val truncated: Boolean = false,
    val absenceReason: DiagnosticsArchiveStageEvidenceAbsenceReason? = null,
)

@Serializable
internal enum class DiagnosticsArchiveStageEvidenceAbsenceReason {
    @SerialName("stage_not_executed")
    STAGE_NOT_EXECUTED,

    @SerialName("stage_session_unavailable")
    STAGE_SESSION_UNAVAILABLE,

    @SerialName("report_unavailable")
    REPORT_UNAVAILABLE,

    @SerialName("not_applicable_for_stage")
    NOT_APPLICABLE_FOR_STAGE,

    @SerialName("attempt_ledger_unavailable")
    ATTEMPT_LEDGER_UNAVAILABLE,

    @SerialName("no_executed_attempts")
    NO_EXECUTED_ATTEMPTS,

    @SerialName("no_observations_emitted")
    NO_OBSERVATIONS_EMITTED,

    @SerialName("no_diagnoses_emitted")
    NO_DIAGNOSES_EMITTED,

    @SerialName("no_snapshots_collected")
    NO_SNAPSHOTS_COLLECTED,

    @SerialName("no_contexts_collected")
    NO_CONTEXTS_COLLECTED,

    @SerialName("no_native_events_collected")
    NO_NATIVE_EVENTS_COLLECTED,

    @SerialName("no_telemetry_samples_collected")
    NO_TELEMETRY_SAMPLES_COLLECTED,
}
