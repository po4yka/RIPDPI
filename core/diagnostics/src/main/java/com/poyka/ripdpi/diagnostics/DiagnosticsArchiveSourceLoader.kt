package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRepository
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named

class DiagnosticsArchiveSourceLoader
    @Inject
    constructor(
        private val historyRepository: DiagnosticsHistoryRepository,
        private val logcatSnapshotCollector: LogcatSnapshotCollector,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        internal suspend fun load(): DiagnosticsArchiveSourceData {
            val sessions = historyRepository.observeRecentScanSessions(limit = 50).first()
            val usageSessions =
                historyRepository.observeBypassUsageSessions(limit = DiagnosticsArchiveFormat.telemetryLimit).first()
            val snapshots =
                historyRepository.observeSnapshots(limit = DiagnosticsArchiveFormat.snapshotLimit).first()
            val telemetry =
                historyRepository.observeTelemetry(limit = DiagnosticsArchiveFormat.telemetryLimit).first()
            val events =
                historyRepository.observeNativeEvents(limit = DiagnosticsArchiveFormat.globalEventLimit).first()
            val contexts =
                historyRepository.observeContexts(limit = DiagnosticsArchiveFormat.snapshotLimit).first()
            val logcatSnapshot = runCatching { logcatSnapshotCollector.capture() }.getOrNull()
            val approachSummaries =
                DiagnosticsSessionQueries.buildApproachSummaries(
                    scanSessions = sessions,
                    usageSessions = usageSessions,
                    json = json,
                )
            return DiagnosticsArchiveSourceData(
                sessions = sessions,
                usageSessions = usageSessions,
                snapshots = snapshots,
                telemetry = telemetry,
                events = events,
                contexts = contexts,
                approachSummaries = approachSummaries,
                logcatSnapshot = logcatSnapshot,
            )
        }

        internal suspend fun getScanSession(sessionId: String): ScanSessionEntity? =
            historyRepository.getScanSession(sessionId)

        internal suspend fun getProbeResults(sessionId: String): List<ProbeResultEntity> =
            historyRepository.getProbeResults(sessionId)
    }
