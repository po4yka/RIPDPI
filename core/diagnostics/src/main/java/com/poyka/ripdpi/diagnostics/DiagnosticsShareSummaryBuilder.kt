package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.data.diagnostics.retryCount
import com.poyka.ripdpi.data.diagnostics.rttBand
import com.poyka.ripdpi.data.diagnostics.winningStrategyFamily
import com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSessionProjection
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private const val ShareSummaryResultPreviewLimit = 5
private const val ShareSummaryWarningPreviewLimit = 3

internal object DiagnosticsShareSummaryBuilder {
    suspend fun build(
        sessionId: String?,
        scanRecordStore: DiagnosticsScanRecordStore,
        artifactReadStore: DiagnosticsArtifactReadStore,
        json: Json,
        projector: DiagnosticsSummaryProjector = DiagnosticsSummaryProjector(),
    ): ShareSummary {
        val selectedSession =
            sessionId
                ?.let { id -> scanRecordStore.getScanSession(id) }
                ?: scanRecordStore.observeRecentScanSessions(limit = 1).first().firstOrNull()
        val selectedResults =
            selectedSession?.id?.let { id -> scanRecordStore.getProbeResults(id) }.orEmpty()
        val latestSnapshot =
            selectedSession
                ?.id
                ?.let { id ->
                    artifactReadStore.observeSnapshots(limit = 200).first().firstOrNull { it.sessionId == id }
                }
                ?: artifactReadStore.observeSnapshots(limit = 1).first().firstOrNull()
        val latestSnapshotModel =
            latestSnapshot
                ?.payloadJson
                ?.let { payload ->
                    runCatching { json.decodeFromString(NetworkSnapshotModel.serializer(), payload) }.getOrNull()
                }
        val latestContext =
            selectedSession
                ?.id
                ?.let { id ->
                    artifactReadStore.observeContexts(limit = 200).first().firstOrNull { it.sessionId == id }
                }
                ?: artifactReadStore.observeContexts(limit = 1).first().firstOrNull()
        val latestContextModel =
            latestContext
                ?.payloadJson
                ?.let { payload ->
                    runCatching { json.decodeFromString(DiagnosticContextModel.serializer(), payload) }.getOrNull()
                }
        val latestTelemetry = artifactReadStore.observeTelemetry(limit = 1).first().firstOrNull()
        val latestWarnings =
            artifactReadStore.observeNativeEvents(limit = 50).first().filter {
                it.level.equals("warn", ignoreCase = true) || it.level.equals("error", ignoreCase = true)
            }
        val selectedReport =
            selectedSession
                ?.reportJson
                ?.let { reportJson ->
                    runCatching { json.decodeEngineScanReportWireCompat(reportJson).toSessionProjection() }.getOrNull()
                }
        val summaryProjection =
            projector.project(
                session = selectedSession,
                report = selectedReport,
                latestSnapshotModel = latestSnapshotModel,
                latestContextModel = latestContextModel,
                latestTelemetry = latestTelemetry,
                selectedResults = selectedResults,
                warnings = latestWarnings,
            )
        val title =
            selectedSession?.let { "RIPDPI diagnostics ${it.id.take(8)}" } ?: "RIPDPI diagnostics summary"
        val body =
            buildBody(
                selectedSession = selectedSession,
                summaryProjection = summaryProjection,
            )
        return ShareSummary(
            title = title,
            body = body.trim(),
            compactMetrics =
                listOfNotNull(
                    selectedSession?.pathMode?.let { SummaryMetric(label = "Path", value = it) },
                    latestSnapshotModel?.transport?.let { SummaryMetric(label = "Transport", value = it) },
                    latestContextModel?.service?.activeMode?.let { SummaryMetric(label = "Mode", value = it) },
                    latestContextModel?.device?.appVersionName?.let { SummaryMetric(label = "App", value = it) },
                    latestTelemetry?.txBytes?.let { SummaryMetric(label = "TX", value = it.toString()) },
                    latestTelemetry?.rxBytes?.let { SummaryMetric(label = "RX", value = it.toString()) },
                ),
        )
    }

    private fun buildBody(
        selectedSession: ScanSessionEntity?,
        summaryProjection: com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSummaryProjection,
    ): String =
        buildString {
            appendLine("RIPDPI diagnostics summary")
            appendLine("session=${selectedSession?.id ?: "latest-live"}")
            summaryProjection.sessionLines.forEach(::appendLine)
            summaryProjection.networkLines.forEach(::appendLine)
            summaryProjection.contextLines.forEach(::appendLine)
            summaryProjection.telemetryLines.forEach(::appendLine)
            summaryProjection.resultLines.forEach(::appendLine)
            appendReportSection(summaryProjection.reportProjection())
            summaryProjection.warningLines.forEach(::appendLine)
        }
}

private fun com.poyka.ripdpi.diagnostics.presentation.DiagnosticsSummaryProjection.reportProjection():
    DiagnosticsSessionProjection? =
    if (
        diagnoses.isEmpty() &&
        classifierVersion == null &&
        packVersions.isEmpty()
    ) {
        null
    } else {
        DiagnosticsSessionProjection(
            diagnoses = diagnoses,
            classifierVersion = classifierVersion,
            packVersions = packVersions,
        )
    }

private fun StringBuilder.appendReportSection(selectedReport: DiagnosticsSessionProjection?) {
    selectedReport ?: return
    selectedReport.classifierVersion?.let { appendLine("classifierVersion=$it") }
    if (selectedReport.diagnoses.isNotEmpty()) {
        appendLine("diagnosisCount=${selectedReport.diagnoses.size}")
        selectedReport.diagnoses.take(ShareSummaryResultPreviewLimit).forEach { diagnosis ->
            appendLine("diagnosis.${diagnosis.code}=${diagnosis.summary}")
        }
    }
    selectedReport.packVersions.forEach { (packId, version) ->
        appendLine("pack.$packId=$version")
    }
}
