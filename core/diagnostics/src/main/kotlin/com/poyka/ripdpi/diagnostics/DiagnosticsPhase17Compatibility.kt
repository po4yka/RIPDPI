package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.diagnostics.application.selectStrategyProbeTargetsForSession as selectStrategyProbeTargetsForSessionImpl
import com.poyka.ripdpi.diagnostics.export.DiagnosticsMeasurementConstants as ExportDiagnosticsMeasurementConstants

internal typealias DiagnosticsArchiveBuildProvenance =
    com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveBuildProvenance
internal typealias DiagnosticsArchiveCompletenessPayload =
    com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveCompletenessPayload
internal typealias DiagnosticsArchiveEntry = com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveEntry
internal typealias DiagnosticsArchiveIntegrityPayload =
    com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveIntegrityPayload
internal typealias DiagnosticsArchiveManifest = com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveManifest
internal typealias DiagnosticsArchiveNativeLibraryProvenance =
    com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveNativeLibraryProvenance
internal typealias DiagnosticsArchivePayload = com.poyka.ripdpi.diagnostics.export.DiagnosticsArchivePayload
internal typealias DiagnosticsArchiveProvenancePayload =
    com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveProvenancePayload
internal typealias DiagnosticsArchiveRenderer = com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRenderer
internal typealias DiagnosticsArchiveRunType = com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRunType
internal typealias DiagnosticsArchiveRuntimeConfigPayload =
    com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRuntimeConfigPayload
internal typealias DiagnosticsArchiveSectionStatus =
    com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSectionStatus
internal typealias DiagnosticsArchiveSelection = com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSelection
internal typealias DiagnosticsArchiveSessionSelectionStatus =
    com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSessionSelectionStatus
internal typealias DiagnosticsArchiveArchiveWideCounts =
    com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveArchiveWideCounts
internal typealias DiagnosticsArchivePrimarySessionCounts =
    com.poyka.ripdpi.diagnostics.export.DiagnosticsArchivePrimarySessionCounts
internal typealias DiagnosticsArchiveRootSourceCounts =
    com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveRootSourceCounts
internal typealias DiagnosticsArchiveScopedCounts =
    com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveScopedCounts
internal typealias DiagnosticsArchiveSourceData = com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSourceData
internal typealias DiagnosticsArchiveTarget = com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveTarget
internal typealias DiagnosticsArchiveZipWriter = com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveZipWriter
typealias ReplayArchiveEntryBuilder = com.poyka.ripdpi.diagnostics.export.ReplayArchiveEntryBuilder
typealias ReplayArchiveRedactor = com.poyka.ripdpi.diagnostics.export.ReplayArchiveRedactor
typealias ReplayResultStore = com.poyka.ripdpi.diagnostics.replay.ReplayResultStore

internal object DiagnosticsArchiveFormat {
    const val directoryName = com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat.directoryName
    const val fileNamePrefix = com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat.fileNamePrefix
    const val schemaVersion = com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat.schemaVersion
    const val privacyMode = com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat.privacyMode
    const val scope = com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat.scope
    const val maxArchiveFiles = com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat.maxArchiveFiles
    const val maxArchiveAgeMs = com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat.maxArchiveAgeMs
    const val telemetryLimit = com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat.telemetryLimit
    const val globalEventLimit = com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat.globalEventLimit
    const val sessionEventLimit = com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat.sessionEventLimit
    const val snapshotLimit = com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat.snapshotLimit

    fun includedFiles(
        logcatIncluded: Boolean,
        fileLogIncluded: Boolean = false,
        startupJournalIncluded: Boolean = false,
        composite: Boolean = false,
        replayIncluded: Boolean = false,
    ): List<String> =
        com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat.includedFiles(
            logcatIncluded = logcatIncluded,
            fileLogIncluded = fileLogIncluded,
            startupJournalIncluded = startupJournalIncluded,
            composite = composite,
            replayIncluded = replayIncluded,
        )
}

internal object DiagnosticsMeasurementConstants {
    const val AcceptanceCorpusVersion = ExportDiagnosticsMeasurementConstants.AcceptanceCorpusVersion
    const val RolloutPolicyVersion = ExportDiagnosticsMeasurementConstants.RolloutPolicyVersion
}

internal object DiagnosticsReportPersister {
    suspend fun persistScanReport(
        report: com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire,
        scanRecordStore: com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore,
        artifactWriteStore: com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore,
        serviceStateStore: com.poyka.ripdpi.data.ServiceStateStore,
        json: kotlinx.serialization.json.Json,
    ) = com.poyka.ripdpi.diagnostics.finalization.DiagnosticsReportPersister.persistScanReport(
        report = report,
        scanRecordStore = scanRecordStore,
        artifactWriteStore = artifactWriteStore,
        serviceStateStore = serviceStateStore,
        json = json,
        deferTerminal = false,
    )

    suspend fun persistScanFailure(
        sessionId: String,
        summary: String,
        scanRecordStore: com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore,
    ) = com.poyka.ripdpi.diagnostics.finalization.DiagnosticsReportPersister.persistScanFailure(
        sessionId = sessionId,
        summary = summary,
        scanRecordStore = scanRecordStore,
    )

    suspend fun persistScanCancellationCause(
        sessionId: String,
        summary: String,
        scanRecordStore: com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore,
    ) = com.poyka.ripdpi.diagnostics.finalization.DiagnosticsReportPersister.persistScanCancellationCause(
        sessionId = sessionId,
        summary = summary,
        scanRecordStore = scanRecordStore,
    )

    suspend fun persistNativeEvents(
        sessionId: String,
        payload: String?,
        artifactWriteStore: com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore,
        json: kotlinx.serialization.json.Json,
    ) = com.poyka.ripdpi.diagnostics.finalization.DiagnosticsReportPersister.persistNativeEvents(
        sessionId = sessionId,
        payload = payload,
        artifactWriteStore = artifactWriteStore,
        json = json,
    )

    suspend fun persistServiceNativeEvents(
        serviceTelemetry: com.poyka.ripdpi.data.ServiceTelemetrySnapshot,
        artifactWriteStore: com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore,
    ) = com.poyka.ripdpi.diagnostics.finalization.DiagnosticsReportPersister.persistServiceNativeEvents(
        serviceTelemetry = serviceTelemetry,
        artifactWriteStore = artifactWriteStore,
    )
}

internal fun selectStrategyProbeTargetsForSession(
    sessionId: String,
    intent: com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent,
    isManual: Boolean = false,
): com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent =
    selectStrategyProbeTargetsForSessionImpl(
        sessionId = sessionId,
        intent = intent,
        isManual = isManual,
    )
