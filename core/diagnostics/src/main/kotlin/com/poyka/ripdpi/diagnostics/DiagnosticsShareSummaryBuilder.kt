package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

internal object DiagnosticsShareSummaryBuilder {
    private const val SessionArtifactLimit = 200
    private const val WarningLimit = 50
    private const val SessionIdPrefixLength = 8

    suspend fun build(
        sessionId: String?,
        scanRecordStore: DiagnosticsScanRecordStore,
        artifactReadStore: DiagnosticsArtifactReadStore,
        json: Json,
        projector: DiagnosticsSummaryProjector = DiagnosticsSummaryProjector(),
    ): ShareSummary {
        val requestedSessionId = sessionId
        val selectedSession = resolveSelectedSession(requestedSessionId, scanRecordStore)
        if (requestedSessionId != null && selectedSession == null) {
            return missingSessionSummary(requestedSessionId)
        }
        val selectedResults =
            selectedSession?.id?.let { id -> scanRecordStore.getProbeResults(id) }.orEmpty()
        val latestSnapshotModel = loadLatestSnapshotModel(selectedSession, artifactReadStore, json)
        val latestContextModel = loadLatestContextModel(selectedSession, artifactReadStore, json)
        val latestTelemetry = loadLatestTelemetry(selectedSession, artifactReadStore)
        val latestWarnings = loadLatestWarnings(selectedSession, artifactReadStore)
        val selectedReport =
            selectedSession
                ?.reportJson
                ?.let { reportJson ->
                    runCatching { json.decodeEngineScanReportWire(reportJson).toSessionProjection() }.getOrNull()
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
            selectedSession?.let { "RIPDPI diagnostics ${it.id.take(SessionIdPrefixLength)}" }
                ?: "RIPDPI diagnostics summary"
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

    private suspend fun resolveSelectedSession(
        requestedSessionId: String?,
        scanRecordStore: DiagnosticsScanRecordStore,
    ): ScanSessionEntity? =
        if (requestedSessionId != null) {
            scanRecordStore.getScanSession(requestedSessionId)
        } else {
            scanRecordStore.observeRecentScanSessions(limit = 1).first().firstOrNull()
        }

    private suspend fun loadLatestSnapshotModel(
        selectedSession: ScanSessionEntity?,
        artifactReadStore: DiagnosticsArtifactReadStore,
        json: Json,
    ): NetworkSnapshotModel? {
        val latestSnapshot =
            selectedSession
                ?.id
                ?.let { id ->
                    artifactReadStore.getSnapshotsForSession(id, limit = SessionArtifactLimit).firstOrNull()
                }
                ?: artifactReadStore.observeSnapshots(limit = 1).first().firstOrNull()
        return latestSnapshot
            ?.payloadJson
            ?.let { payload ->
                runCatching { json.decodeFromString(NetworkSnapshotModel.serializer(), payload) }.getOrNull()
            }
    }

    private suspend fun loadLatestContextModel(
        selectedSession: ScanSessionEntity?,
        artifactReadStore: DiagnosticsArtifactReadStore,
        json: Json,
    ): DiagnosticContextModel? {
        val latestContext =
            selectedSession
                ?.id
                ?.let { id ->
                    artifactReadStore.getContextsForSession(id, limit = SessionArtifactLimit).firstOrNull()
                }
                ?: artifactReadStore.observeContexts(limit = 1).first().firstOrNull()
        return latestContext
            ?.payloadJson
            ?.let { payload ->
                runCatching { json.decodeFromString(DiagnosticContextModel.serializer(), payload) }.getOrNull()
            }
    }

    private suspend fun loadLatestTelemetry(
        selectedSession: ScanSessionEntity?,
        artifactReadStore: DiagnosticsArtifactReadStore,
    ) = selectedSession
        ?.id
        ?.let { id ->
            artifactReadStore.observeTelemetry(limit = SessionArtifactLimit).first().firstOrNull {
                it.sessionId == id
            }
        }
        ?: artifactReadStore.observeTelemetry(limit = 1).first().firstOrNull()

    private suspend fun loadLatestWarnings(
        selectedSession: ScanSessionEntity?,
        artifactReadStore: DiagnosticsArtifactReadStore,
    ) = if (selectedSession != null) {
        artifactReadStore.getNativeEventsForSession(selectedSession.id, limit = WarningLimit)
    } else {
        artifactReadStore.observeNativeEvents(limit = WarningLimit).first()
    }.filter { event ->
        event.level.equals("warn", ignoreCase = true) || event.level.equals("error", ignoreCase = true)
    }

    private fun missingSessionSummary(requestedSessionId: String): ShareSummary =
        ShareSummary(
            title = "RIPDPI diagnostics ${requestedSessionId.take(SessionIdPrefixLength)}",
            body =
                listOf(
                    "RIPDPI diagnostics summary",
                    "session=$requestedSessionId",
                    "status=session_unavailable",
                ).joinToString(separator = "\n"),
            compactMetrics = emptyList(),
        )
}
