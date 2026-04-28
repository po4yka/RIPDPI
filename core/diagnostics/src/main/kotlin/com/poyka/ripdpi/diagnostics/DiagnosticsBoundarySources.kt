package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.ActiveConnectionPolicyStore
import com.poyka.ripdpi.data.diagnostics.BypassUsageHistoryStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultDiagnosticsHistorySource
    @Inject
    constructor(
        private val bypassUsageHistoryStore: BypassUsageHistoryStore,
        private val scanRecordStore: DiagnosticsScanRecordStore,
        private val artifactReadStore: DiagnosticsArtifactReadStore,
        private val mapper: DiagnosticsBoundaryMapper,
    ) : DiagnosticsHistorySource {
        override fun observeConnectionSessions(limit: Int): Flow<List<DiagnosticConnectionSession>> =
            bypassUsageHistoryStore.observeBypassUsageSessions(limit).map { sessions ->
                sessions.map(mapper::toDiagnosticConnectionSession)
            }

        override fun observeDiagnosticsSessions(limit: Int): Flow<List<DiagnosticScanSession>> =
            scanRecordStore.observeRecentScanSessions(limit).map { sessions ->
                sessions.map(mapper::toDiagnosticScanSession)
            }

        override fun observeNativeEvents(limit: Int): Flow<List<DiagnosticEvent>> =
            artifactReadStore.observeNativeEvents(limit).map { events ->
                events.map(mapper::toDiagnosticEvent)
            }

        override suspend fun loadConnectionDetail(sessionId: String): DiagnosticConnectionDetail? =
            withContext(Dispatchers.IO) {
                val session = bypassUsageHistoryStore.getBypassUsageSession(sessionId) ?: return@withContext null
                DiagnosticConnectionDetail(
                    session = mapper.toDiagnosticConnectionSession(session),
                    snapshots =
                        artifactReadStore
                            .observeConnectionSnapshots(sessionId, limit = 40)
                            .first()
                            .map(mapper::toDiagnosticNetworkSnapshot),
                    contexts =
                        artifactReadStore
                            .observeConnectionContexts(sessionId, limit = 20)
                            .first()
                            .map(mapper::toDiagnosticContextSnapshot),
                    telemetry =
                        artifactReadStore
                            .observeConnectionTelemetry(sessionId, limit = 60)
                            .first()
                            .map(mapper::toDiagnosticTelemetrySample),
                    events =
                        artifactReadStore
                            .observeConnectionNativeEvents(sessionId, limit = 80)
                            .first()
                            .map(mapper::toDiagnosticEvent),
                )
            }
    }

@Singleton
class DefaultDiagnosticsRememberedPolicySource
    @Inject
    constructor(
        private val rememberedNetworkPolicyStore: RememberedNetworkPolicyStore,
        private val networkDnsPathPreferenceStore: NetworkDnsPathPreferenceStore,
        private val mapper: DiagnosticsBoundaryMapper,
    ) : DiagnosticsRememberedPolicySource {
        override fun observePolicies(limit: Int): Flow<List<DiagnosticsRememberedPolicy>> =
            rememberedNetworkPolicyStore.observePolicies(limit).map { policies ->
                policies.map(mapper::toDiagnosticsRememberedPolicy)
            }

        override suspend fun clearAll() {
            rememberedNetworkPolicyStore.clearAll()
            networkDnsPathPreferenceStore.clearAll()
        }
    }

@Singleton
class DefaultDiagnosticsActiveConnectionPolicySource
    @Inject
    constructor(
        private val activeConnectionPolicyStore: ActiveConnectionPolicyStore,
        private val mapper: DiagnosticsBoundaryMapper,
        @ApplicationIoScope
        scope: CoroutineScope,
    ) : DiagnosticsActiveConnectionPolicySource {
        override val activePolicies: StateFlow<Map<Mode, DiagnosticActiveConnectionPolicy>> =
            activeConnectionPolicyStore.activePolicies
                .map { policies ->
                    policies.mapValues { (_, policy) -> mapper.toDiagnosticActiveConnectionPolicy(policy) }
                }.stateIn(
                    scope = scope,
                    // Eagerly is intentional: this is a singleton bound to ApplicationIoScope.
                    // Active connection policies must be available immediately when diagnostics
                    // consumers query this source; deferring to WhileSubscribed would produce a
                    // stale initial value for the first subscriber.
                    started = SharingStarted.Eagerly,
                    initialValue = emptyMap(),
                )
    }
