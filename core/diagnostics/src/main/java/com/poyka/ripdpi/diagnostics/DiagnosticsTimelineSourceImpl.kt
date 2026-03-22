package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.diagnostics.BypassUsageHistoryStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
        private val mapper: DiagnosticsBoundaryMapper,
        @param:Named("diagnosticsJson")
        private val json: Json,
    ) : DiagnosticsTimelineSource {
        constructor(
            profileCatalog: DiagnosticsProfileCatalog,
            scanRecordStore: DiagnosticsScanRecordStore,
            artifactReadStore: DiagnosticsArtifactReadStore,
            bypassUsageHistoryStore: BypassUsageHistoryStore,
            json: Json,
        ) : this(
            profileCatalog = profileCatalog,
            scanRecordStore = scanRecordStore,
            artifactReadStore = artifactReadStore,
            bypassUsageHistoryStore = bypassUsageHistoryStore,
            mapper = DiagnosticsBoundaryMapper(json),
            json = json,
        )

        private val activeProgressState = MutableStateFlow<ScanProgress?>(null)

        override val activeScanProgress: StateFlow<ScanProgress?> = activeProgressState.asStateFlow()
        override val profiles: Flow<List<DiagnosticProfile>> =
            profileCatalog.observeProfiles().map { profiles -> profiles.map(mapper::toDiagnosticProfile) }
        override val sessions: Flow<List<DiagnosticScanSession>> =
            scanRecordStore.observeRecentScanSessions().map { sessions -> sessions.map(mapper::toDiagnosticScanSession) }
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
        override val snapshots: Flow<List<DiagnosticNetworkSnapshot>> =
            artifactReadStore.observeSnapshots().map { snapshots -> snapshots.map(mapper::toDiagnosticNetworkSnapshot) }
        override val contexts: Flow<List<DiagnosticContextSnapshot>> =
            artifactReadStore.observeContexts().map { contexts -> contexts.map(mapper::toDiagnosticContextSnapshot) }
        override val telemetry: Flow<List<DiagnosticTelemetrySample>> =
            artifactReadStore.observeTelemetry().map { telemetry -> telemetry.map(mapper::toDiagnosticTelemetrySample) }
        override val nativeEvents: Flow<List<DiagnosticEvent>> =
            artifactReadStore.observeNativeEvents().map { events -> events.map(mapper::toDiagnosticEvent) }
        override val exports: Flow<List<DiagnosticExportRecord>> =
            artifactReadStore.observeExportRecords().map { exports -> exports.map(mapper::toDiagnosticExportRecord) }

        internal fun updateActiveScanProgress(progress: ScanProgress?) {
            activeProgressState.value = progress
        }
    }
