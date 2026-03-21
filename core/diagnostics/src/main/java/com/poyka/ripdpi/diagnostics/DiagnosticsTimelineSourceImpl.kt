package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.BypassUsageHistoryStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
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
        private val profileCatalog: DiagnosticsProfileCatalog,
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val artifactReadStore: DiagnosticsArtifactReadStore,
        private val bypassUsageHistoryStore: BypassUsageHistoryStore,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) : DiagnosticsTimelineSource {
        private val activeProgressState = MutableStateFlow<ScanProgress?>(null)

        override val activeScanProgress: StateFlow<ScanProgress?> = activeProgressState.asStateFlow()
        override val profiles: Flow<List<DiagnosticProfileEntity>> = profileCatalog.observeProfiles()
        override val sessions: Flow<List<ScanSessionEntity>> = scanRecordStore.observeRecentScanSessions()
        override val approachStats: Flow<List<BypassApproachSummary>> =
            combine(
                scanRecordStore.observeRecentScanSessions(limit = 200),
                bypassUsageHistoryStore.observeBypassUsageSessions(limit = 200),
            ) { scanSessions, usageSessions ->
                DiagnosticsSessionQueries.buildApproachSummaries(
                    scanSessions = scanSessions,
                    usageSessions = usageSessions,
                    json = json,
                )
            }
        override val snapshots: Flow<List<NetworkSnapshotEntity>> = artifactReadStore.observeSnapshots()
        override val contexts: Flow<List<DiagnosticContextEntity>> = artifactReadStore.observeContexts()
        override val telemetry: Flow<List<TelemetrySampleEntity>> = artifactReadStore.observeTelemetry()
        override val nativeEvents: Flow<List<NativeSessionEventEntity>> = artifactReadStore.observeNativeEvents()
        override val exports: Flow<List<ExportRecordEntity>> = artifactReadStore.observeExportRecords()

        internal fun updateActiveScanProgress(progress: ScanProgress?) {
            activeProgressState.value = progress
        }
    }
