@file:Suppress("MaxLineLength")

package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.BypassUsageHistoryStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named

class DiagnosticsArchiveSourceLoader
    @Inject
    constructor(
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val artifactReadStore: DiagnosticsArtifactReadStore,
        private val bypassUsageHistoryStore: BypassUsageHistoryStore,
        private val logcatSnapshotCollector: LogcatSnapshotCollector,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) {
        internal suspend fun load(): DiagnosticsArchiveSourceData {
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
            scanRecordStore.getScanSession(sessionId)

        internal suspend fun getProbeResults(sessionId: String): List<ProbeResultEntity> =
            scanRecordStore.getProbeResults(sessionId)
    }
