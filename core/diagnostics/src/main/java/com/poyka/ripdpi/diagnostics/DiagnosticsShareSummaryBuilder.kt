package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

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
            DiagnosticsSummaryTextRenderer.render(
                document = summaryProjection,
                preludeLines =
                    listOf(
                        "RIPDPI diagnostics summary",
                        "session=${selectedSession?.id ?: "latest-live"}",
                    ),
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
}
