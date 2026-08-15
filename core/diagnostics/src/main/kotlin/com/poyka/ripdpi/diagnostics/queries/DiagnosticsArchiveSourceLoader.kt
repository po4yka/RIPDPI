@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.StartupJournal
import com.poyka.ripdpi.data.diagnostics.BypassUsageHistoryStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactQueryStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveBuildInfoProvider
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveFormat
import com.poyka.ripdpi.diagnostics.export.DiagnosticsArchiveSourceData
import com.poyka.ripdpi.diagnostics.replay.ReplayResultStore
import kotlinx.coroutines.CancellationException
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
        private val artifactQueryStore: DiagnosticsArtifactQueryStore,
        private val bypassUsageHistoryStore: BypassUsageHistoryStore,
        private val logcatSnapshotCollector: LogcatSnapshotCollector,
        private val fileLogWriter: FileLogWriter,
        private val startupJournal: StartupJournal,
        private val buildInfoProvider: DiagnosticsArchiveBuildInfoProvider,
        private val diagnosticsHomeCompositeRunService: DiagnosticsHomeCompositeRunService,
        private val replayResultStore: ReplayResultStore,
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
                artifactReadStore.observeSnapshots(limit = DiagnosticsArchiveFormat.snapshotLimit + 1).first()
            val telemetry =
                artifactReadStore.observeTelemetry(limit = DiagnosticsArchiveFormat.telemetryLimit + 1).first()
            val events =
                artifactQueryStore.getGlobalNativeEvents(limit = DiagnosticsArchiveFormat.globalEventLimit + 1)
            val contexts =
                artifactReadStore.observeContexts(limit = DiagnosticsArchiveFormat.snapshotLimit + 1).first()
            val earliestSessionStart = sessions.minOfOrNull { it.startedAt }
            val logcatCapture =
                runCatching { logcatSnapshotCollector.capture(sinceTimestampMs = earliestSessionStart) }
                    .onFailure { error ->
                        when (error) {
                            is CancellationException -> throw error
                            is Error -> throw error
                            else -> Logger.w { diagnosticsArchiveCaptureFailureLogText("logcat", error) }
                        }
                    }
            val logcatSnapshot = logcatCapture.getOrNull()
            val fileLogCapture =
                fileLogWriter
                    .readLogSnapshotResult()
                    .onFailure { error -> Logger.w { diagnosticsArchiveCaptureFailureLogText("app_log", error) } }
            val fileLogSnapshot = fileLogCapture.getOrNull()
            val startupJournalSnapshot = startupJournal.snapshot().takeIf { it.byteCount > 0 }
            val approachSummaries =
                DiagnosticsSessionQueries.buildApproachSummaries(
                    scanSessions = sessions,
                    usageSessions = usageSessions,
                    json = json,
                )
            val collectionWarnings =
                buildList {
                    logcatCapture.exceptionOrNull()?.let { error ->
                        add("logcat_capture_failed:${error::class.simpleName ?: "unknown"}")
                    }
                    fileLogCapture.exceptionOrNull()?.let { error ->
                        add("app_log_capture_failed:${error::class.simpleName ?: "unknown"}")
                    }
                    if (telemetry.size > DiagnosticsArchiveFormat.telemetryLimit) {
                        add("telemetry_samples_truncated_at_${DiagnosticsArchiveFormat.telemetryLimit}")
                    }
                    if (events.size > DiagnosticsArchiveFormat.globalEventLimit) {
                        add("native_events_truncated_at_${DiagnosticsArchiveFormat.globalEventLimit}")
                    }
                    if (snapshots.size > DiagnosticsArchiveFormat.snapshotLimit) {
                        add("network_snapshots_truncated_at_${DiagnosticsArchiveFormat.snapshotLimit}")
                    }
                    if (contexts.size > DiagnosticsArchiveFormat.snapshotLimit) {
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
                startupJournalSnapshot = startupJournalSnapshot,
                replayResults = replayResultStore.recent(),
                installedArtifact = buildInfoProvider.installedArtifact(),
            )
        }

        internal suspend fun getScanSession(sessionId: String): ScanSessionEntity? =
            scanRecordStore.getScanSession(sessionId)

        internal suspend fun getScanSessions(sessionIds: List<String>): List<ScanSessionEntity> =
            sessionIds.mapNotNull { sessionId -> scanRecordStore.getScanSession(sessionId) }

        internal suspend fun getProbeResults(sessionId: String): List<ProbeResultEntity> =
            scanRecordStore.getProbeResults(sessionId)

        internal suspend fun getNativeEvents(sessionId: String): List<NativeSessionEventEntity> =
            artifactQueryStore.getNativeEventsForSession(
                sessionId = sessionId,
                limit = DiagnosticsArchiveFormat.sessionEventLimit + 1,
            )

        internal suspend fun getSnapshots(sessionId: String): List<NetworkSnapshotEntity> =
            artifactQueryStore.getSnapshotsForSession(
                sessionId = sessionId,
                limit = DiagnosticsArchiveFormat.snapshotLimit + 1,
            )

        internal suspend fun getContexts(sessionId: String): List<DiagnosticContextEntity> =
            artifactQueryStore.getContextsForSession(
                sessionId = sessionId,
                limit = DiagnosticsArchiveFormat.snapshotLimit + 1,
            )

        internal suspend fun getStageTelemetry(
            session: ScanSessionEntity,
            connectionSessionIds: Set<String>,
        ): List<TelemetrySampleEntity> =
            artifactQueryStore.getTelemetryForArchiveStage(
                sessionId = session.id,
                connectionSessionIds = connectionSessionIds.ifEmpty { setOf("") }.toList(),
                startedAt = session.startedAt,
                finishedAt = session.finishedAt ?: session.startedAt,
                limit = DiagnosticsArchiveFormat.telemetryLimit + 1,
            )

        internal suspend fun getCompletedHomeRun(runId: String): DiagnosticsHomeCompositeOutcome? =
            diagnosticsHomeCompositeRunService.getCompletedRun(runId)
    }

internal fun diagnosticsArchiveCaptureFailureLogText(
    captureKind: String,
    error: Throwable,
): String =
    "Diagnostics archive capture failed: captureKind=$captureKind errorClass=${error::class.simpleName ?: "unknown"}"
