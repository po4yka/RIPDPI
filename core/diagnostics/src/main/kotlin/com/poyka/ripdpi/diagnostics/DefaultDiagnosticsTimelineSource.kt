package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.diagnostics.BypassUsageHistoryStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DefaultDiagnosticsTimelineSource
    @Inject
    constructor(
        private val profileCatalog: DiagnosticsProfileCatalog,
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val artifactReadStore: DiagnosticsArtifactReadStore,
        private val bypassUsageHistoryStore: BypassUsageHistoryStore,
        private val mapper: DiagnosticsBoundaryMapper,
        @param:ApplicationIoScope
        private val scope: CoroutineScope,
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
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            json = json,
        )

        private val activeProgressState = MutableStateFlow<ScanProgress?>(null)

        override val activeScanProgress: StateFlow<ScanProgress?> = activeProgressState.asStateFlow()
        override val activeConnectionSession: StateFlow<DiagnosticConnectionSession?> =
            bypassUsageHistoryStore
                .observeBypassUsageSessions(limit = 200)
                .map { sessions ->
                    sessions
                        .filter { session -> session.finishedAt == null }
                        .maxByOrNull { session -> maxOf(session.updatedAt, session.startedAt) }
                        ?.let(mapper::toDiagnosticConnectionSession)
                }.distinctUntilChanged()
                .stateIn(
                    scope = scope,
                    started = SharingStarted.Eagerly,
                    initialValue = null,
                )
        private val activeConnectionSessionId: Flow<String?> =
            activeConnectionSession.map { session -> session?.id }.distinctUntilChanged()
        override val profiles: Flow<List<DiagnosticProfile>> =
            profileCatalog.observeProfiles().map { profiles -> profiles.map(mapper::toDiagnosticProfile) }
        override val sessions: Flow<List<DiagnosticScanSession>> =
            scanRecordStore.observeRecentScanSessions().map { sessions ->
                sessions.map(mapper::toDiagnosticScanSession)
            }
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
        override val liveSnapshots: Flow<List<DiagnosticNetworkSnapshot>> =
            activeConnectionSessionId.flatMapLatest { connectionSessionId ->
                if (connectionSessionId == null) {
                    flowOf(emptyList())
                } else {
                    artifactReadStore.observeConnectionSnapshots(connectionSessionId).map { snapshots ->
                        snapshots
                            .filter { snapshot -> snapshot.snapshotKind == ConnectionSampleArtifactKind }
                            .map(mapper::toDiagnosticNetworkSnapshot)
                    }
                }
            }
        override val liveContexts: Flow<List<DiagnosticContextSnapshot>> =
            activeConnectionSessionId.flatMapLatest { connectionSessionId ->
                if (connectionSessionId == null) {
                    flowOf(emptyList())
                } else {
                    artifactReadStore.observeConnectionContexts(connectionSessionId).map { contexts ->
                        contexts
                            .filter { context -> context.contextKind == ConnectionSampleArtifactKind }
                            .map(mapper::toDiagnosticContextSnapshot)
                    }
                }
            }
        override val liveTelemetry: Flow<List<DiagnosticTelemetrySample>> =
            activeConnectionSessionId.flatMapLatest { connectionSessionId ->
                if (connectionSessionId == null) {
                    flowOf(emptyList())
                } else {
                    artifactReadStore.observeConnectionTelemetry(connectionSessionId).map { telemetry ->
                        telemetry.map(mapper::toDiagnosticTelemetrySample)
                    }
                }
            }
        override val liveNativeEvents: Flow<List<DiagnosticEvent>> =
            activeConnectionSessionId.flatMapLatest { connectionSessionId ->
                if (connectionSessionId == null) {
                    flowOf(emptyList())
                } else {
                    artifactReadStore.observeConnectionNativeEvents(connectionSessionId).map { events ->
                        events.map(mapper::toDiagnosticEvent)
                    }
                }
            }
        override val exports: Flow<List<DiagnosticExportRecord>> =
            artifactReadStore.observeExportRecords().map { exports -> exports.map(mapper::toDiagnosticExportRecord) }

        internal fun updateActiveScanProgress(progress: ScanProgress?) {
            activeProgressState.value = progress
        }
    }

private const val ConnectionSampleArtifactKind = "connection_sample"
