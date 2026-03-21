package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRepository
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class DefaultDiagnosticsTimelineSource
    @Inject
    constructor(
        private val historyRepository: DiagnosticsHistoryRepository,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) : DiagnosticsTimelineSource {
        private val activeProgressState = MutableStateFlow<ScanProgress?>(null)

        override val activeScanProgress: StateFlow<ScanProgress?> = activeProgressState.asStateFlow()
        override val profiles: Flow<List<DiagnosticProfileEntity>> = historyRepository.observeProfiles()
        override val sessions: Flow<List<ScanSessionEntity>> = historyRepository.observeRecentScanSessions()
        override val approachStats: Flow<List<BypassApproachSummary>> =
            combine(
                historyRepository.observeRecentScanSessions(limit = 200),
                historyRepository.observeBypassUsageSessions(limit = 200),
            ) { scanSessions, usageSessions ->
                DiagnosticsSessionQueries.buildApproachSummaries(
                    scanSessions = scanSessions,
                    usageSessions = usageSessions,
                    json = json,
                )
            }
        override val snapshots: Flow<List<NetworkSnapshotEntity>> = historyRepository.observeSnapshots()
        override val contexts: Flow<List<DiagnosticContextEntity>> = historyRepository.observeContexts()
        override val telemetry: Flow<List<TelemetrySampleEntity>> = historyRepository.observeTelemetry()
        override val nativeEvents: Flow<List<NativeSessionEventEntity>> = historyRepository.observeNativeEvents()
        override val exports: Flow<List<ExportRecordEntity>> = historyRepository.observeExportRecords()

        internal fun updateActiveScanProgress(progress: ScanProgress?) {
            activeProgressState.value = progress
        }
    }
