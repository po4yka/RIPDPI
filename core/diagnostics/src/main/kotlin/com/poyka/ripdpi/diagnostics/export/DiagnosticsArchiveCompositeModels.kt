package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageSummary
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
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
    val unavailableReason: String? = null,
    val recommendationContributor: Boolean = false,
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
    val detectionProvenance: DiagnosticsArchiveDetectionProvenance? = null,
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
