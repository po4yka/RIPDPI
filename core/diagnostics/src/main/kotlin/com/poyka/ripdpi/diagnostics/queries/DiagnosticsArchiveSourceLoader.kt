package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.diagnostics.BypassUsageHistoryStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveBuildInfoProvider
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSourceData
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named

internal class DiagnosticsArchiveSourceLoader
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val artifactReadStore: DiagnosticsArtifactReadStore,
        private val bypassUsageHistoryStore: BypassUsageHistoryStore,
        private val logcatSnapshotCollector: LogcatSnapshotCollector,
        private val fileLogWriter: FileLogWriter,
        private val buildInfoProvider: DiagnosticsArchiveBuildInfoProvider,
        private val diagnosticsHomeCompositeRunService: DiagnosticsHomeCompositeRunService,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        internal suspend fun load(): DiagnosticsArchiveSourceData {
            val appSettings = appSettingsRepository.snapshot()
            val sessions = scanRecordStore.observeRecentScanSessions(limit = 50).first()
            val usageSessions =
                bypassUsageHistoryStore
                    .observeBypassUsageSessions(
                        limit = DiagnosticsArchiveFormat.telemetryLimit,
                    ).first()
            val snapshots =
                artifactReadStore.observeSnapshots(limit = DiagnosticsArchiveFormat.snapshotLimit).first()
            val telemetry =
                artifactReadStore.observeTelemetry(limit = DiagnosticsArchiveFormat.telemetryLimit).first()
            val events =
                artifactReadStore.observeNativeEvents(limit = DiagnosticsArchiveFormat.globalEventLimit).first()
            val contexts =
                artifactReadStore.observeContexts(limit = DiagnosticsArchiveFormat.snapshotLimit).first()
            val earliestSessionStart = sessions.minOfOrNull { it.startedAt }
            val logcatCapture = runCatching { logcatSnapshotCollector.capture(sinceTimestampMs = earliestSessionStart) }
            val logcatSnapshot = logcatCapture.getOrNull()
            val fileLogSnapshot = runCatching { fileLogWriter.readLogContent() }.getOrNull()
            val approachSummaries =
                DiagnosticsSessionQueries.buildApproachSummaries(
                    scanSessions = sessions,
                    usageSessions = usageSessions,
                    json = json,
                )
            val collectionWarnings =
                buildList {
                    logcatCapture.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }?.let { message ->
                        add("logcat_capture_failed:$message")
                    }
                    if (telemetry.size >= DiagnosticsArchiveFormat.telemetryLimit) {
                        add("telemetry_samples_truncated_at_${DiagnosticsArchiveFormat.telemetryLimit}")
                    }
                    if (events.size >= DiagnosticsArchiveFormat.globalEventLimit) {
                        add("native_events_truncated_at_${DiagnosticsArchiveFormat.globalEventLimit}")
                    }
                    if (snapshots.size >= DiagnosticsArchiveFormat.snapshotLimit) {
                        add("network_snapshots_truncated_at_${DiagnosticsArchiveFormat.snapshotLimit}")
                    }
                    if (contexts.size >= DiagnosticsArchiveFormat.snapshotLimit) {
                        add("diagnostic_contexts_truncated_at_${DiagnosticsArchiveFormat.snapshotLimit}")
                    }
                }
            return DiagnosticsArchiveSourceData(
                sessions = sessions,
                usageSessions = usageSessions,
                snapshots = snapshots,
                telemetry = telemetry,
                events = events,
                contexts = contexts,
                approachSummaries = approachSummaries,
                appSettings = appSettings,
                buildProvenance = buildInfoProvider.buildProvenance(),
                collectionWarnings = collectionWarnings,
                logcatSnapshot = logcatSnapshot,
                fileLogSnapshot = fileLogSnapshot,
            )
        }

        internal suspend fun getScanSession(sessionId: String): ScanSessionEntity? =
            scanRecordStore.getScanSession(sessionId)

        internal suspend fun getScanSessions(sessionIds: List<String>): List<ScanSessionEntity> =
            sessionIds.mapNotNull { sessionId -> scanRecordStore.getScanSession(sessionId) }

        internal suspend fun getProbeResults(sessionId: String): List<ProbeResultEntity> =
            scanRecordStore.getProbeResults(sessionId)

        internal suspend fun getCompletedHomeRun(runId: String): DiagnosticsHomeCompositeOutcome? =
            diagnosticsHomeCompositeRunService.getCompletedRun(runId)
    }
