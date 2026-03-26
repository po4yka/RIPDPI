package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

internal object DiagnosticsShareSummaryBuilder {
    private const val SessionArtifactLimit = 200
    private const val WarningLimit = 50

    @Suppress("CyclomaticComplexMethod")
    suspend fun build(
        sessionId: String?,
        scanRecordStore: DiagnosticsScanRecordStore,
        artifactReadStore: DiagnosticsArtifactReadStore,
        json: Json,
        projector: DiagnosticsSummaryProjector = DiagnosticsSummaryProjector(),
    ): ShareSummary {
        val requestedSessionId = sessionId
        val selectedSession =
            if (requestedSessionId != null) {
                scanRecordStore.getScanSession(requestedSessionId)
            } else {
                scanRecordStore.observeRecentScanSessions(limit = 1).first().firstOrNull()
            }
        if (requestedSessionId != null && selectedSession == null) {
            return missingSessionSummary(requestedSessionId)
        }
        val selectedResults =
            selectedSession?.id?.let { id -> scanRecordStore.getProbeResults(id) }.orEmpty()
        val latestSnapshot =
            selectedSession
                ?.id
                ?.let { id ->
                    artifactReadStore.getSnapshotsForSession(id, limit = SessionArtifactLimit).firstOrNull()
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
                    artifactReadStore.getContextsForSession(id, limit = SessionArtifactLimit).firstOrNull()
                }
                ?: artifactReadStore.observeContexts(limit = 1).first().firstOrNull()
        val latestContextModel =
            latestContext
                ?.payloadJson
                ?.let { payload ->
                    runCatching { json.decodeFromString(DiagnosticContextModel.serializer(), payload) }.getOrNull()
                }
        val latestTelemetry =
            selectedSession
                ?.id
                ?.let { id ->
                    artifactReadStore.observeTelemetry(limit = SessionArtifactLimit).first().firstOrNull {
                        it.sessionId ==
                            id
                    }
                }
                ?: artifactReadStore.observeTelemetry(limit = 1).first().firstOrNull()
        val latestWarnings =
            if (selectedSession != null) {
                artifactReadStore.getNativeEventsForSession(selectedSession.id, limit = WarningLimit)
            } else {
                artifactReadStore.observeNativeEvents(limit = WarningLimit).first()
            }.filter { event ->
                event.level.equals("warn", ignoreCase = true) || event.level.equals("error", ignoreCase = true)
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

    private fun missingSessionSummary(requestedSessionId: String): ShareSummary =
        ShareSummary(
            title = "RIPDPI diagnostics ${requestedSessionId.take(8)}",
            body =
                listOf(
                    "RIPDPI diagnostics summary",
                    "session=$requestedSessionId",
                    "status=session_unavailable",
                ).joinToString(separator = "\n"),
            compactMetrics = emptyList(),
        )
}
