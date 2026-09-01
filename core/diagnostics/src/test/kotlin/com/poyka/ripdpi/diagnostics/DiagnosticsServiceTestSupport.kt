package com.poyka.ripdpi.diagnostics

import android.content.ContextWrapper
import com.poyka.ripdpi.core.NetworkDiagnosticsBridge
import com.poyka.ripdpi.core.NetworkDiagnosticsBridgeFactory
import com.poyka.ripdpi.core.testing.FaultOutcome
import com.poyka.ripdpi.core.testing.FaultQueue
import com.poyka.ripdpi.core.testing.FaultSpec
import com.poyka.ripdpi.core.testing.faultThrowable
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DiagnosticsInPathRouteLease
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.OrderedServiceStateStore
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.PreferredEdgeCandidate
import com.poyka.ripdpi.data.RawPathExecutionCancelledException
import com.poyka.ripdpi.data.RawPathExecutionOutcome
import com.poyka.ripdpi.data.RawPathExecutionResult
import com.poyka.ripdpi.data.RawPathExecutionSettlement
import com.poyka.ripdpi.data.RawPathExecutionSettlementOutcome
import com.poyka.ripdpi.data.RawPathRuntimeContext
import com.poyka.ripdpi.data.RawPathRuntimeStatus
import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServerCapabilityObservation
import com.poyka.ripdpi.data.ServerCapabilityRecord
import com.poyka.ripdpi.data.ServerCapabilityScope
import com.poyka.ripdpi.data.ServerCapabilityStore
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceHistoryEvent
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TemporaryResolverOverride
import com.poyka.ripdpi.data.WifiNetworkIdentityTuple
import com.poyka.ripdpi.data.diagnostics.BypassUsageHistoryStore
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArchiveNativeEventQueryStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactQueryStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsDurableStateEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsExportRecordStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsFailureArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryClock
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRetentionStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHomeDetectionLaunchOriginStorageValue
import com.poyka.ripdpi.data.diagnostics.DiagnosticsNativeEventArchiveClass
import com.poyka.ripdpi.data.diagnostics.DiagnosticsNativeEventArchiveSource
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsTerminalOutboxStore
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.HomeDiagnosticsRunEntity
import com.poyka.ripdpi.data.diagnostics.HomeDiagnosticsRunStore
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceEntity
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceRecordStore
import com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceEntity
import com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceRecordStore
import com.poyka.ripdpi.data.diagnostics.NetworkEdgePreferenceStore
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.RawPathSettlementDurableStatePrefix
import com.poyka.ripdpi.data.diagnostics.RawPathSettlementStore
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyRecordStore
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TargetPackVersionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.data.diagnostics.TerminalOutboxDurableStatePrefix
import com.poyka.ripdpi.data.diagnostics.TerminalPolicyDependencyDurableStatePrefix
import com.poyka.ripdpi.data.diagnostics.archiveEventClass
import com.poyka.ripdpi.data.diagnostics.archiveEventClassCounts
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProbePersistencePolicyWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileExecutionPolicyWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileSpecWire
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal class FakeAppSettingsRepository(
    initialSettings: AppSettings = defaultDiagnosticsAppSettings(),
) : AppSettingsRepository {
    private val state = MutableStateFlow(initialSettings)

    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value =
            state.value
                .toBuilder()
                .apply(transform)
                .build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}

internal class FakeLogcatSnapshotCollector(
    private val snapshot: LogcatSnapshot? = null,
    private val failure: Throwable? = null,
) : LogcatSnapshotCollector() {
    override suspend fun capture(sinceTimestampMs: Long?): LogcatSnapshot? {
        failure?.let { throw it }
        return snapshot
    }
}

internal class TestContext(
    private val testCacheDir: File = Files.createTempDirectory("diagnostics-service-test").toFile(),
) : ContextWrapper(null) {
    private val testFilesDir: File = File(testCacheDir, "files").apply { mkdirs() }

    override fun getCacheDir(): File = testCacheDir

    override fun getFilesDir(): File = testFilesDir

    override fun getNoBackupFilesDir(): File = File(testCacheDir, "no-backup").apply { mkdirs() }
}

internal class FakeDiagnosticsHistoryStores :
    DiagnosticsProfileCatalog,
    DiagnosticsScanRecordStore,
    DiagnosticsArtifactReadStore,
    DiagnosticsArtifactQueryStore,
    DiagnosticsArchiveNativeEventQueryStore,
    DiagnosticsArtifactWriteStore,
    DiagnosticsFailureArtifactWriteStore,
    DiagnosticsExportRecordStore,
    HomeDiagnosticsRunStore,
    BypassUsageHistoryStore,
    DiagnosticsTerminalOutboxStore,
    RememberedNetworkPolicyRecordStore,
    NetworkDnsPathPreferenceRecordStore,
    NetworkEdgePreferenceRecordStore,
    DiagnosticsHistoryRetentionStore {
    val profilesState = MutableStateFlow<List<DiagnosticProfileEntity>>(emptyList())
    val sessionsState = MutableStateFlow<List<ScanSessionEntity>>(emptyList())
    val snapshotsState = MutableStateFlow<List<NetworkSnapshotEntity>>(emptyList())
    val contextsState = MutableStateFlow<List<DiagnosticContextEntity>>(emptyList())
    val telemetryState = MutableStateFlow<List<TelemetrySampleEntity>>(emptyList())
    val nativeEventsState = MutableStateFlow<List<NativeSessionEventEntity>>(emptyList())
    val exportsState = MutableStateFlow<List<ExportRecordEntity>>(emptyList())
    val usageSessionsState = MutableStateFlow<List<BypassUsageSessionEntity>>(emptyList())
    val terminalOutboxState = MutableStateFlow<List<DiagnosticsDurableStateEntity>>(emptyList())
    val homeRunsState = MutableStateFlow<List<HomeDiagnosticsRunEntity>>(emptyList())
    val rememberedPoliciesState = MutableStateFlow<List<RememberedNetworkPolicyEntity>>(emptyList())
    val networkDnsPathPreferencesState = MutableStateFlow<List<NetworkDnsPathPreferenceEntity>>(emptyList())
    val networkEdgePreferencesState = MutableStateFlow<List<NetworkEdgePreferenceEntity>>(emptyList())
    val usageSessionsCollectorCount = AtomicInteger(0)
    val failureArtifactBatchCount = AtomicInteger(0)
    val rawPathSettlementCommitCount = AtomicInteger(0)
    val rawPathSettlementStore = FakeRawPathSettlementStore(this)
    val observedTelemetryConnectionSessionIds = CopyOnWriteArrayList<String>()
    var beforeInsertNativeSessionEvent: suspend (NativeSessionEventEntity) -> Unit = {}
    var beforeInsertExportRecord: suspend (ExportRecordEntity) -> Unit = {}
    var afterInsertExportRecord: suspend (ExportRecordEntity) -> Unit = {}
    var beforeGetExportRecords: suspend () -> Unit = {}
    var beforeInsertTelemetrySample: suspend (TelemetrySampleEntity) -> Unit = {}
    var beforeUpsertBypassUsageSession: suspend (BypassUsageSessionEntity) -> Unit = {}
    var beforeUpsertRememberedNetworkPolicy: suspend (RememberedNetworkPolicyEntity) -> Unit = {}
    var beforeCheckpointTerminalOutbox: suspend (DiagnosticsDurableStateEntity) -> Unit = {}
    var afterCheckpointTerminalOutbox: suspend (DiagnosticsDurableStateEntity) -> Unit = {}
    var afterCompleteTerminalOutbox: suspend () -> Unit = {}
    var beforeCheckpointTerminalPolicy: suspend (RememberedNetworkPolicyEntity?) -> Unit = {}
    var beforeCheckpointTerminalSession: suspend (BypassUsageSessionEntity) -> Unit = {}
    var afterInsertNativeSessionEvent: suspend (NativeSessionEventEntity) -> Unit = {}
    var afterUpsertScanSession: suspend (ScanSessionEntity) -> Unit = {}
    var beforePersistCompletedScan: suspend (ScanSessionEntity) -> Unit = {}
    var beforeUpsertSnapshot: suspend (NetworkSnapshotEntity) -> Unit = {}
    var afterUpsertSnapshot: suspend (NetworkSnapshotEntity) -> Unit = {}
    var afterUpsertContextSnapshot: suspend (DiagnosticContextEntity) -> Unit = {}
    var beforeRawPathSettlementTerminalWrite: suspend (ScanSessionEntity) -> Unit = {}
    var currentTime: Long = Long.MAX_VALUE
    private val packVersions = mutableMapOf<String, TargetPackVersionEntity>()
    private val probeResults = mutableMapOf<String, List<ProbeResultEntity>>()

    override fun observeProfiles(): Flow<List<DiagnosticProfileEntity>> = profilesState

    override fun observeRecentScanSessions(limit: Int): Flow<List<ScanSessionEntity>> =
        sessionsState.map { sessions ->
            sessions
                .filter { session -> session.launchOrigin != DiagnosticsHomeDetectionLaunchOriginStorageValue }
                .take(limit)
        }

    override fun observeSnapshots(limit: Int): Flow<List<NetworkSnapshotEntity>> =
        snapshotsState.map { snapshots -> snapshots.take(limit) }

    override suspend fun getSnapshotsForSession(
        sessionId: String,
        limit: Int,
    ): List<NetworkSnapshotEntity> = snapshotsState.value.filter { it.sessionId == sessionId }.take(limit)

    override fun observeConnectionSnapshots(
        connectionSessionId: String,
        limit: Int,
    ): Flow<List<NetworkSnapshotEntity>> =
        snapshotsState.map { snapshots ->
            snapshots.filter { it.connectionSessionId == connectionSessionId }.take(limit)
        }

    override fun observeContexts(limit: Int): Flow<List<DiagnosticContextEntity>> =
        contextsState.map { contexts -> contexts.take(limit) }

    override suspend fun getContextsForSession(
        sessionId: String,
        limit: Int,
    ): List<DiagnosticContextEntity> = contextsState.value.filter { it.sessionId == sessionId }.take(limit)

    override fun observeConnectionContexts(
        connectionSessionId: String,
        limit: Int,
    ): Flow<List<DiagnosticContextEntity>> =
        contextsState.map { contexts ->
            contexts.filter { it.connectionSessionId == connectionSessionId }.take(limit)
        }

    override fun observeTelemetry(limit: Int): Flow<List<TelemetrySampleEntity>> = telemetryState

    override suspend fun getLatestTelemetrySampleForFingerprint(
        activeMode: String,
        fingerprintHash: String,
        createdAfter: Long,
    ): TelemetrySampleEntity? =
        telemetryState.value
            .asSequence()
            .filter { sample ->
                sample.activeMode == activeMode &&
                    sample.telemetryNetworkFingerprintHash == fingerprintHash &&
                    sample.createdAt >= createdAfter
            }.maxByOrNull { it.createdAt }

    override suspend fun getTelemetryForArchiveStage(
        sessionId: String,
        connectionSessionIds: List<String>,
        startedAt: Long,
        finishedAt: Long,
        limit: Int,
    ): List<TelemetrySampleEntity> =
        telemetryState.value
            .asSequence()
            .filter { sample -> sample.createdAt in startedAt..finishedAt }
            .filter { sample ->
                sample.sessionId == sessionId ||
                    sample.connectionSessionId in connectionSessionIds
            }.sortedByDescending(TelemetrySampleEntity::createdAt)
            .take(limit)
            .toList()

    override fun observeConnectionTelemetry(
        connectionSessionId: String,
        limit: Int,
    ): Flow<List<TelemetrySampleEntity>> {
        observedTelemetryConnectionSessionIds += connectionSessionId
        return telemetryState.map { telemetry ->
            telemetry.filter { it.connectionSessionId == connectionSessionId }.take(limit)
        }
    }

    override fun observeNativeEvents(limit: Int): Flow<List<NativeSessionEventEntity>> = nativeEventsState

    override suspend fun getNativeEventsForSession(
        sessionId: String,
        limit: Int,
    ): List<NativeSessionEventEntity> = nativeEventsState.value.filter { it.sessionId == sessionId }.take(limit)

    override suspend fun getNativeEventArchiveSourceForSession(
        sessionId: String,
        newestLimit: Int,
        criticalClassLimit: Int,
    ): DiagnosticsNativeEventArchiveSource =
        nativeEventsState.value
            .filter { event -> event.sessionId == sessionId }
            .toBoundedNativeEventArchiveSource(newestLimit, criticalClassLimit)

    override suspend fun getRelayAttemptTraceEvents(
        connectionSessionId: String,
        runtimeId: String,
        attemptId: Long,
        limit: Int,
    ): List<NativeSessionEventEntity> =
        nativeEventsState.value
            .asSequence()
            .filter { event ->
                event.connectionSessionId == connectionSessionId &&
                    event.runtimeId == runtimeId &&
                    event.attemptId == attemptId &&
                    event.attemptSequence != null &&
                    event.subsystem == "relay"
            }.sortedWith(
                compareBy<NativeSessionEventEntity>({ it.attemptSequence }, { it.createdAt }, { it.id }),
            ).take(limit)
            .toList()

    override suspend fun getNativeEventById(id: String): NativeSessionEventEntity? =
        nativeEventsState.value.firstOrNull { it.id == id }

    override suspend fun getGlobalNativeEvents(limit: Int): List<NativeSessionEventEntity> =
        nativeEventsState.value.filter { it.sessionId == null }.take(limit)

    override suspend fun getGlobalNativeEventArchiveSource(
        newestLimit: Int,
        criticalClassLimit: Int,
    ): DiagnosticsNativeEventArchiveSource =
        nativeEventsState.value
            .filter { event -> event.sessionId == null }
            .toBoundedNativeEventArchiveSource(newestLimit, criticalClassLimit)

    override fun observeConnectionNativeEvents(
        connectionSessionId: String,
        limit: Int,
    ): Flow<List<NativeSessionEventEntity>> =
        nativeEventsState.map { events ->
            events
                .filter { it.connectionSessionId == connectionSessionId }
                .sortedByDescending(NativeSessionEventEntity::createdAt)
                .take(limit)
        }

    override fun observeConnectionRootCauseEvents(
        connectionSessionId: String,
        limit: Int,
    ): Flow<List<NativeSessionEventEntity>> =
        nativeEventsState.map { events ->
            events
                .filter { it.connectionSessionId == connectionSessionId }
                .filterNot { it.subsystem == "network_transition" }
                .sortedByDescending(NativeSessionEventEntity::createdAt)
                .take(limit)
        }

    override fun observeConnectionNetworkTransitionEvents(
        connectionSessionId: String,
    ): Flow<List<NativeSessionEventEntity>> =
        nativeEventsState.map { events ->
            events.filter { event ->
                event.connectionSessionId == connectionSessionId && event.subsystem == "network_transition"
            }
        }

    override suspend fun getNativeSessionEvent(eventId: String): NativeSessionEventEntity? =
        nativeEventsState.value.find { event -> event.id == eventId }

    override suspend fun getPendingTerminalOutboxes(limit: Int): List<DiagnosticsDurableStateEntity> =
        terminalOutboxState.value
            .filter { state -> state.key.startsWith(TerminalOutboxDurableStatePrefix) }
            .sortedBy(DiagnosticsDurableStateEntity::updatedAt)
            .take(limit)

    override suspend fun getTerminalOutbox(key: String): DiagnosticsDurableStateEntity? =
        terminalOutboxState.value.firstOrNull { state -> state.key == key }

    override fun observeExportRecords(limit: Int): Flow<List<ExportRecordEntity>> = exportsState

    override fun observeBypassUsageSessions(limit: Int): Flow<List<BypassUsageSessionEntity>> =
        flow {
            usageSessionsCollectorCount.incrementAndGet()
            try {
                emitAll(usageSessionsState)
            } finally {
                usageSessionsCollectorCount.decrementAndGet()
            }
        }

    override fun observeRememberedNetworkPolicies(limit: Int): Flow<List<RememberedNetworkPolicyEntity>> =
        rememberedPoliciesState

    override suspend fun getProfile(id: String): DiagnosticProfileEntity? = profilesState.value.find { it.id == id }

    override suspend fun getPackVersion(packId: String): TargetPackVersionEntity? = packVersions[packId]

    override suspend fun getScanSession(sessionId: String): ScanSessionEntity? =
        sessionsState.value.find { it.id == sessionId }

    override suspend fun getHomeRun(runId: String): HomeDiagnosticsRunEntity? =
        homeRunsState.value.find { it.runId == runId }

    override suspend fun upsertHomeRun(run: HomeDiagnosticsRunEntity) {
        homeRunsState.value = homeRunsState.value.upsertById(run) { it.runId }
    }

    override suspend fun persistCompletedHomeRun(
        run: HomeDiagnosticsRunEntity,
        detectionSession: ScanSessionEntity?,
        detectionResults: List<ProbeResultEntity>,
    ) {
        check(detectionSession != null || detectionResults.isEmpty()) {
            "Local detection probe results require a detection scan session"
        }
        detectionSession?.let { session ->
            persistCompletedScan(session, detectionResults)
        }
        upsertHomeRun(run)
    }

    override suspend fun getBypassUsageSession(sessionId: String): BypassUsageSessionEntity? =
        usageSessionsState.value.find { it.id == sessionId }

    override suspend fun getRememberedNetworkPolicy(
        fingerprintHash: String,
        mode: String,
    ): RememberedNetworkPolicyEntity? =
        rememberedPoliciesState.value.find { it.fingerprintHash == fingerprintHash && it.mode == mode }

    override suspend fun getRememberedNetworkPolicyById(id: Long): RememberedNetworkPolicyEntity? =
        rememberedPoliciesState.value.find { it.id == id }

    override suspend fun getNetworkDnsPathPreference(fingerprintHash: String): NetworkDnsPathPreferenceEntity? =
        networkDnsPathPreferencesState.value.find { it.fingerprintHash == fingerprintHash }

    override suspend fun getNetworkEdgePreference(
        fingerprintHash: String,
        host: String,
        transportKind: String,
    ): NetworkEdgePreferenceEntity? =
        networkEdgePreferencesState.value.find { preference ->
            preference.fingerprintHash == fingerprintHash &&
                preference.host == host &&
                preference.transportKind == transportKind
        }

    override suspend fun getNetworkEdgePreferencesForFingerprint(
        fingerprintHash: String,
    ): List<NetworkEdgePreferenceEntity> =
        networkEdgePreferencesState.value.filter { it.fingerprintHash == fingerprintHash }

    override suspend fun findValidatedRememberedNetworkPolicy(
        fingerprintHash: String,
        mode: String,
    ): RememberedNetworkPolicyEntity? =
        rememberedPoliciesState.value.find { policy ->
            policy.fingerprintHash == fingerprintHash &&
                policy.mode == mode &&
                policy.status == com.poyka.ripdpi.data.RememberedNetworkPolicyStatusValidated &&
                (policy.suppressedUntil?.let { it <= currentTime } != false)
        }

    override suspend fun getProbeResults(sessionId: String): List<ProbeResultEntity> = probeResults[sessionId].orEmpty()

    override suspend fun upsertProfile(profile: DiagnosticProfileEntity) {
        profilesState.value = profilesState.value.upsertById(profile) { it.id }
    }

    override suspend fun upsertPackVersion(version: TargetPackVersionEntity) {
        packVersions[version.packId] = version
    }

    override suspend fun upsertScanSession(session: ScanSessionEntity) {
        sessionsState.value = sessionsState.value.upsertById(session) { it.id }
        afterUpsertScanSession(session)
    }

    override suspend fun persistCompletedScan(
        session: ScanSessionEntity,
        results: List<ProbeResultEntity>,
    ) {
        beforePersistCompletedScan(session)
        sessionsState.value = sessionsState.value.upsertById(session) { it.id }
        probeResults[session.id] = results
        afterUpsertScanSession(session)
    }

    override suspend fun replaceProbeResults(
        sessionId: String,
        results: List<ProbeResultEntity>,
    ) {
        probeResults[sessionId] = results
    }

    override suspend fun upsertSnapshot(snapshot: NetworkSnapshotEntity) {
        beforeUpsertSnapshot(snapshot)
        snapshotsState.value = snapshotsState.value + snapshot
        afterUpsertSnapshot(snapshot)
    }

    override suspend fun upsertContextSnapshot(snapshot: DiagnosticContextEntity) {
        contextsState.value = contextsState.value + snapshot
        afterUpsertContextSnapshot(snapshot)
    }

    override suspend fun insertTelemetrySample(sample: TelemetrySampleEntity) {
        beforeInsertTelemetrySample(sample)
        telemetryState.value = telemetryState.value + sample
    }

    override suspend fun insertNativeSessionEvent(event: NativeSessionEventEntity) {
        beforeInsertNativeSessionEvent(event)
        nativeEventsState.value = nativeEventsState.value.upsertById(event) { it.id }
        afterInsertNativeSessionEvent(event)
    }

    override suspend fun persistFailureArtifacts(
        usageSession: BypassUsageSessionEntity,
        snapshot: NetworkSnapshotEntity?,
        context: DiagnosticContextEntity?,
        telemetry: TelemetrySampleEntity,
        event: NativeSessionEventEntity,
    ) {
        failureArtifactBatchCount.incrementAndGet()
        beforeUpsertBypassUsageSession(usageSession)
        beforeInsertTelemetrySample(telemetry)
        beforeInsertNativeSessionEvent(event)

        usageSessionsState.value = usageSessionsState.value.upsertById(usageSession) { it.id }
        snapshot?.let { snapshotsState.value = snapshotsState.value.upsertById(it) { value -> value.id } }
        context?.let { contextsState.value = contextsState.value.upsertById(it) { value -> value.id } }
        telemetryState.value = telemetryState.value.upsertById(telemetry) { it.id }
        nativeEventsState.value = nativeEventsState.value.upsertById(event) { it.id }

        snapshot?.let { afterUpsertSnapshot(it) }
        context?.let { afterUpsertContextSnapshot(it) }
        afterInsertNativeSessionEvent(event)
    }

    override suspend fun insertExportRecord(record: ExportRecordEntity) {
        beforeInsertExportRecord(record)
        exportsState.value = exportsState.value + record
        afterInsertExportRecord(record)
    }

    override suspend fun getExportRecords(): List<ExportRecordEntity> {
        beforeGetExportRecords()
        return exportsState.value
    }

    override suspend fun deleteExportRecords(recordIds: List<String>) {
        exportsState.value = exportsState.value.filterNot { it.id in recordIds }
    }

    override suspend fun upsertBypassUsageSession(session: BypassUsageSessionEntity) {
        beforeUpsertBypassUsageSession(session)
        usageSessionsState.value = usageSessionsState.value.upsertById(session) { it.id }
    }

    override suspend fun beginTerminalOutbox(
        finishedSession: BypassUsageSessionEntity,
        marker: DiagnosticsDurableStateEntity,
        policyDependency: DiagnosticsDurableStateEntity?,
    ): DiagnosticsDurableStateEntity {
        beforeUpsertBypassUsageSession(finishedSession)
        usageSessionsState.value = usageSessionsState.value.upsertById(finishedSession) { it.id }
        val current = terminalOutboxState.value.firstOrNull { it.key == marker.key }
        if (current == null) {
            terminalOutboxState.value =
                terminalOutboxState.value
                    .upsertById(marker) { it.key }
                    .let { states ->
                        if (policyDependency == null) {
                            states
                        } else {
                            states.upsertById(policyDependency) { it.key }
                        }
                    }
        }
        return current ?: marker
    }

    override suspend fun checkpointTerminalOutbox(
        expectedMarker: DiagnosticsDurableStateEntity,
        replacementMarker: DiagnosticsDurableStateEntity,
    ): Boolean {
        beforeCheckpointTerminalOutbox(replacementMarker)
        return replaceTerminalMarker(expectedMarker, replacementMarker)
    }

    override suspend fun checkpointTerminalArtifacts(
        events: List<NativeSessionEventEntity>,
        telemetrySample: TelemetrySampleEntity?,
        expectedMarker: DiagnosticsDurableStateEntity,
        replacementMarker: DiagnosticsDurableStateEntity,
    ): Boolean {
        beforeCheckpointTerminalOutbox(replacementMarker)
        if (!terminalMarkerIsCurrent(expectedMarker)) return false
        events.forEach { event ->
            beforeInsertNativeSessionEvent(event)
            nativeEventsState.value = nativeEventsState.value.upsertById(event) { it.id }
            afterInsertNativeSessionEvent(event)
        }
        telemetrySample?.let { sample ->
            beforeInsertTelemetrySample(sample)
            telemetryState.value = telemetryState.value.upsertById(sample) { it.id }
        }
        return replaceTerminalMarker(expectedMarker, replacementMarker)
    }

    override suspend fun checkpointTerminalPolicy(
        policy: RememberedNetworkPolicyEntity?,
        expectedMarker: DiagnosticsDurableStateEntity,
        replacementMarker: DiagnosticsDurableStateEntity,
    ): Boolean {
        beforeCheckpointTerminalPolicy(policy)
        if (!terminalMarkerIsCurrent(expectedMarker)) return false
        policy?.let { value ->
            rememberedPoliciesState.value = rememberedPoliciesState.value.upsertById(value) { it.id }
        }
        return replaceTerminalMarker(expectedMarker, replacementMarker)
    }

    override suspend fun checkpointTerminalSession(
        finishedSession: BypassUsageSessionEntity,
        expectedMarker: DiagnosticsDurableStateEntity,
        replacementMarker: DiagnosticsDurableStateEntity,
    ): Boolean {
        beforeCheckpointTerminalSession(finishedSession)
        if (!terminalMarkerIsCurrent(expectedMarker)) return false
        usageSessionsState.value = usageSessionsState.value.upsertById(finishedSession) { it.id }
        return replaceTerminalMarker(expectedMarker, replacementMarker)
    }

    override suspend fun completeTerminalOutbox(
        marker: DiagnosticsDurableStateEntity,
        retainPolicyDependency: Boolean,
    ): Boolean {
        if (!terminalMarkerIsCurrent(marker)) return false
        terminalOutboxState.value =
            terminalOutboxState.value.filterNot { state ->
                state.key == marker.key ||
                    (!retainPolicyDependency && state.key == terminalPolicyDependencyKey(marker.key))
            }
        afterCompleteTerminalOutbox()
        return true
    }

    override suspend fun completeTerminalOutboxWithAssessment(
        assessment: NativeSessionEventEntity,
        marker: DiagnosticsDurableStateEntity,
        retainPolicyDependency: Boolean,
    ): Boolean {
        if (!terminalMarkerIsCurrent(marker)) return false
        beforeInsertNativeSessionEvent(assessment)
        nativeEventsState.value = nativeEventsState.value.upsertById(assessment) { it.id }
        afterInsertNativeSessionEvent(assessment)
        terminalOutboxState.value =
            terminalOutboxState.value.filterNot { state ->
                state.key == marker.key ||
                    (!retainPolicyDependency && state.key == terminalPolicyDependencyKey(marker.key))
            }
        afterCompleteTerminalOutbox()
        return true
    }

    private suspend fun replaceTerminalMarker(
        expectedMarker: DiagnosticsDurableStateEntity,
        replacementMarker: DiagnosticsDurableStateEntity,
    ): Boolean {
        if (!terminalMarkerIsCurrent(expectedMarker)) return false
        terminalOutboxState.value = terminalOutboxState.value.upsertById(replacementMarker) { it.key }
        afterCheckpointTerminalOutbox(replacementMarker)
        return true
    }

    private fun terminalMarkerIsCurrent(marker: DiagnosticsDurableStateEntity): Boolean =
        terminalOutboxState.value.firstOrNull { state -> state.key == marker.key }?.value == marker.value

    private fun terminalPolicyDependencyKey(markerKey: String): String =
        "$TerminalPolicyDependencyDurableStatePrefix${markerKey.removePrefix(TerminalOutboxDurableStatePrefix)}"

    override suspend fun upsertRememberedNetworkPolicy(policy: RememberedNetworkPolicyEntity): Long {
        beforeUpsertRememberedNetworkPolicy(policy)
        val persisted =
            if (policy.id == 0L) {
                policy.copy(id = (rememberedPoliciesState.value.maxOfOrNull { it.id } ?: 0L) + 1L)
            } else {
                policy
            }
        rememberedPoliciesState.value = rememberedPoliciesState.value.upsertById(persisted) { it.id }
        return persisted.id
    }

    override suspend fun upsertNetworkDnsPathPreference(preference: NetworkDnsPathPreferenceEntity): Long {
        val persisted =
            if (preference.id == 0L) {
                preference.copy(id = (networkDnsPathPreferencesState.value.maxOfOrNull { it.id } ?: 0L) + 1L)
            } else {
                preference
            }
        networkDnsPathPreferencesState.value = networkDnsPathPreferencesState.value.upsertById(persisted) { it.id }
        return persisted.id
    }

    override suspend fun upsertNetworkEdgePreference(preference: NetworkEdgePreferenceEntity): Long {
        val persisted =
            if (preference.id == 0L) {
                preference.copy(id = (networkEdgePreferencesState.value.maxOfOrNull { it.id } ?: 0L) + 1L)
            } else {
                preference
            }
        networkEdgePreferencesState.value = networkEdgePreferencesState.value.upsertById(persisted) { it.id }
        return persisted.id
    }

    override suspend fun clearRememberedNetworkPolicies() {
        rememberedPoliciesState.value = emptyList()
    }

    override suspend fun deleteRememberedNetworkPolicy(id: Long) {
        rememberedPoliciesState.value = rememberedPoliciesState.value.filterNot { it.id == id }
    }

    override suspend fun countRememberedNetworkPoliciesForFingerprint(fingerprintHash: String): Int =
        rememberedPoliciesState.value.count { it.fingerprintHash == fingerprintHash }

    override suspend fun clearNetworkDnsPathPreferences() {
        networkDnsPathPreferencesState.value = emptyList()
    }

    override suspend fun deleteNetworkDnsPathPreferencesForFingerprint(fingerprintHash: String) {
        networkDnsPathPreferencesState.value =
            networkDnsPathPreferencesState.value.filterNot { it.fingerprintHash == fingerprintHash }
    }

    override suspend fun clearNetworkEdgePreferences() {
        networkEdgePreferencesState.value = emptyList()
    }

    override suspend fun deleteNetworkEdgePreferencesForFingerprint(fingerprintHash: String) {
        networkEdgePreferencesState.value =
            networkEdgePreferencesState.value.filterNot { it.fingerprintHash == fingerprintHash }
    }

    override suspend fun pruneRememberedNetworkPolicies() = Unit

    override suspend fun pruneNetworkDnsPathPreferences() = Unit

    override suspend fun pruneNetworkEdgePreferences() = Unit

    override suspend fun trimOldData(retentionDays: Int) = Unit

    fun storedProbeResults(sessionId: String): List<ProbeResultEntity> = probeResults[sessionId].orEmpty()

    fun seedDefaultProfile(json: Json) {
        profilesState.value =
            listOf(
                DiagnosticProfileEntity(
                    id = "default",
                    name = "Default",
                    source = "bundled",
                    version = 1,
                    requestJson =
                        diagnosticsProfileRequestJson(
                            json = json,
                            profileId = "default",
                            displayName = "Default",
                            targets =
                                DiagnosticsProfileTargets(
                                    domainTargets = listOf(DomainTarget(host = "example.org")),
                                    dnsTargets = listOf(DnsTarget(domain = "blocked.example")),
                                ),
                        ),
                    updatedAt = 1L,
                ),
            )
    }

    fun seedStrategyProbeProfile(
        json: Json,
        profileId: String = "automatic-probing",
        name: String = "Automatic probing",
        suiteId: String = "quick_v1",
        family: DiagnosticProfileFamily =
            if (profileId == "automatic-audit") {
                DiagnosticProfileFamily.AUTOMATIC_AUDIT
            } else {
                DiagnosticProfileFamily.AUTOMATIC_PROBING
            },
    ) {
        profilesState.value =
            listOf(
                DiagnosticProfileEntity(
                    id = profileId,
                    name = name,
                    source = "bundled",
                    version = 1,
                    requestJson =
                        diagnosticsProfileRequestJson(
                            json = json,
                            profileId = profileId,
                            displayName = name,
                            kind = ScanKind.STRATEGY_PROBE,
                            family = family,
                            targets =
                                DiagnosticsProfileTargets(
                                    domainTargets = listOf(DomainTarget(host = "example.org")),
                                    quicTargets = listOf(QuicTarget(host = "example.org")),
                                    strategyProbe = StrategyProbeRequest(suiteId = suiteId),
                                ),
                            allowBackground = family == DiagnosticProfileFamily.AUTOMATIC_PROBING,
                            requiresRawPath = true,
                            manualOnly = family == DiagnosticProfileFamily.AUTOMATIC_AUDIT,
                            probePersistencePolicy =
                                if (family == DiagnosticProfileFamily.AUTOMATIC_PROBING) {
                                    ProbePersistencePolicyWire.BACKGROUND_ONLY
                                } else {
                                    ProbePersistencePolicyWire.MANUAL_ONLY
                                },
                        ),
                    updatedAt = 1L,
                ),
            )
    }
}

internal class TestDiagnosticsHistoryClock(
    var currentTime: Long = 1_000L,
) : DiagnosticsHistoryClock {
    override fun now(): Long = currentTime
}

internal class FakeRawPathSettlementStore(
    private val stores: FakeDiagnosticsHistoryStores,
) : RawPathSettlementStore {
    override suspend fun stageRawPathSettlement(marker: DiagnosticsDurableStateEntity): DiagnosticsDurableStateEntity {
        val current = stores.terminalOutboxState.value.firstOrNull { state -> state.key == marker.key }
        if (current == null) {
            stores.terminalOutboxState.value =
                stores.terminalOutboxState.value.upsertById(marker) { it.key }
        }
        return current ?: marker
    }

    override suspend fun getPendingRawPathSettlements(limit: Int): List<DiagnosticsDurableStateEntity> =
        stores.terminalOutboxState.value
            .filter { state -> state.key.startsWith(RawPathSettlementDurableStatePrefix) }
            .take(limit)

    override suspend fun commitRawPathSettlement(
        marker: DiagnosticsDurableStateEntity,
        context: DiagnosticContextEntity,
        terminalSession: ScanSessionEntity,
    ): Boolean {
        stores.rawPathSettlementCommitCount.incrementAndGet()
        if (stores.terminalOutboxState.value
                .firstOrNull { it.key == marker.key }
                ?.value != marker.value
        ) {
            return false
        }
        val previousContexts = stores.contextsState.value
        val previousSessions = stores.sessionsState.value
        val previousMarkers = stores.terminalOutboxState.value
        val commit =
            runCatching {
                stores.contextsState.value = stores.contextsState.value.upsertById(context) { it.id }
                stores.beforeRawPathSettlementTerminalWrite(terminalSession)
                stores.sessionsState.value = stores.sessionsState.value.upsertById(terminalSession) { it.id }
                stores.terminalOutboxState.value =
                    stores.terminalOutboxState.value.filterNot { state -> state.key == marker.key }
                stores.afterUpsertContextSnapshot(context)
                stores.afterUpsertScanSession(terminalSession)
            }
        commit.exceptionOrNull()?.let { failure ->
            stores.contextsState.value = previousContexts
            stores.sessionsState.value = previousSessions
            stores.terminalOutboxState.value = previousMarkers
            throw failure
        }
        return true
    }

    override suspend fun quarantineMalformedRawPathSettlement(
        marker: DiagnosticsDurableStateEntity,
        quarantineMarker: DiagnosticsDurableStateEntity,
        sessionId: String,
        terminalSummary: String,
        finishedAt: Long,
    ): Boolean {
        if (stores.terminalOutboxState.value
                .firstOrNull { it.key == marker.key }
                ?.value != marker.value
        ) {
            return false
        }
        val previousSessions = stores.sessionsState.value
        val previousMarkers = stores.terminalOutboxState.value
        val quarantine =
            runCatching {
                stores.sessionsState.value =
                    stores.sessionsState.value.map { session ->
                        if (session.id == sessionId && session.status != "failed") {
                            session.copy(status = "failed", summary = terminalSummary, finishedAt = finishedAt)
                        } else {
                            session
                        }
                    }
                stores.terminalOutboxState.value =
                    stores.terminalOutboxState.value
                        .filterNot { state -> state.key == marker.key }
                        .upsertById(quarantineMarker) { it.key }
            }
        quarantine.exceptionOrNull()?.let { failure ->
            stores.sessionsState.value = previousSessions
            stores.terminalOutboxState.value = previousMarkers
            throw failure
        }
        return true
    }
}

internal class FakeNetworkMetadataProvider : NetworkMetadataProvider {
    override suspend fun captureSnapshot(includePublicIp: Boolean): NetworkSnapshotModel = networkSnapshotModelForTest()
}

internal class FakeNetworkFingerprintProvider : NetworkFingerprintProvider {
    override fun capture(): NetworkFingerprint =
        NetworkFingerprint(
            transport = "wifi",
            networkValidated = true,
            captivePortalDetected = false,
            privateDnsMode = "system",
            dnsServers = listOf("1.1.1.1"),
            wifi =
                WifiNetworkIdentityTuple(
                    ssid = "ripdpi-lab",
                    bssid = "aa:bb:cc:dd:ee:ff",
                    gateway = "192.0.2.1",
                ),
        )
}

internal class MutableNetworkFingerprintProvider(
    var fingerprint: NetworkFingerprint? =
        NetworkFingerprint(
            transport = "wifi",
            networkValidated = true,
            captivePortalDetected = false,
            privateDnsMode = "system",
            dnsServers = listOf("1.1.1.1"),
            wifi =
                WifiNetworkIdentityTuple(
                    ssid = "ripdpi-lab",
                    bssid = "aa:bb:cc:dd:ee:ff",
                    gateway = "192.0.2.1",
                ),
        ),
) : NetworkFingerprintProvider {
    override fun capture(): NetworkFingerprint? = fingerprint
}

internal class FakeDiagnosticsContextProvider(
    private val serviceStatus: String = "Running",
    private val activeMode: String = "Proxy",
    private val proxyListenerAddress: String? = null,
) : DiagnosticsContextProvider {
    override suspend fun captureContext(): DiagnosticContextModel = captureContextForTest()

    fun captureContextForTest(): DiagnosticContextModel =
        DiagnosticContextModel(
            service =
                ServiceContextModel(
                    serviceStatus = serviceStatus,
                    configuredMode = "VPN",
                    activeMode = activeMode,
                    selectedProfileId = "default",
                    selectedProfileName = "Default",
                    configSource = "ui",
                    proxyEndpoint = "127.0.0.1:1080",
                    desyncMethod = "split",
                    chainSummary = "tcp: split(1)",
                    routeGroup = "3",
                    sessionUptimeMs = 15_000L,
                    lastNativeErrorHeadline = "none",
                    restartCount = 2,
                    hostAutolearnEnabled = "enabled",
                    learnedHostCount = 4,
                    penalizedHostCount = 1,
                    lastAutolearnHost = "example.org",
                    lastAutolearnGroup = "3",
                    lastAutolearnAction = "host_promoted",
                    proxy =
                        proxyListenerAddress?.let {
                            RuntimeComponentSummary(
                                state = "running",
                                health = "healthy",
                                listenerAddress = it,
                            )
                        },
                ),
            permissions =
                PermissionContextModel(
                    vpnPermissionState = "enabled",
                    notificationPermissionState = "enabled",
                    batteryOptimizationState = "disabled",
                    dataSaverState = "disabled",
                ),
            device =
                DeviceContextModel(
                    appVersionName = "0.0.1",
                    appVersionCode = 1L,
                    buildType = "debug",
                    androidVersion = "16",
                    apiLevel = 36,
                    manufacturer = "Google",
                    model = "Pixel",
                    primaryAbi = "arm64-v8a",
                    locale = "en-US",
                    timezone = "UTC",
                ),
            environment =
                EnvironmentContextModel(
                    batterySaverState = "disabled",
                    powerSaveModeState = "disabled",
                    networkMeteredState = "disabled",
                    roamingState = "disabled",
                ),
        )
}

internal class FakeNetworkDiagnosticsBridgeFactory(
    private val json: Json,
) : NetworkDiagnosticsBridgeFactory {
    val bridge = FakeNetworkDiagnosticsBridge(json)
    var beforeCreate: () -> Unit = {}

    override fun create(): NetworkDiagnosticsBridge {
        beforeCreate()
        return bridge
    }
}

internal enum class DiagnosticsBridgeFaultTarget {
    START_SCAN,
    CANCEL,
    POLL_PROGRESS,
    TAKE_REPORT,
    PASSIVE_EVENTS,
    DESTROY,
}

private sealed interface DiagnosticsBridgeStep {
    data class Payload(
        val value: String?,
    ) : DiagnosticsBridgeStep

    data class Failure(
        val error: Throwable,
    ) : DiagnosticsBridgeStep
}

internal class FakeNetworkDiagnosticsBridge(
    private val json: Json,
) : NetworkDiagnosticsBridge {
    var startedRequestJson: String? = null
    var startedSessionId: String? = null
    var autoCompleteOnStart: Boolean = true
    var startScanEntered: CompletableDeferred<Unit>? = null
    var releaseStartScan: CompletableDeferred<Unit>? = null
    var afterStartScan: suspend () -> Unit = {}
    var requireActiveContextOnDestroy: Boolean = false
    var destroyEntered: CompletableDeferred<Unit>? = null
    var releaseDestroy: CompletableDeferred<Unit>? = null
    var destroyCompleted: CompletableDeferred<Unit>? = null
    var destroyIgnoresCancellation: Boolean = false
    var cancelCount: Int = 0
    var destroyCount: Int = 0
    var lastTakenReportJson: String? = null
        private set
    var lastTakenReportJsonAtDestroy: String? = null
        private set
    val faults = FaultQueue<DiagnosticsBridgeFaultTarget>()
    private val passiveEventsPayloads = ArrayDeque<String>()
    private val scriptedProgress = ArrayDeque<DiagnosticsBridgeStep>()
    private val scriptedReports = ArrayDeque<DiagnosticsBridgeStep>()
    private val scriptedPassiveEvents = ArrayDeque<DiagnosticsBridgeStep>()
    private var reportJson: String? = null
    private var progressJson: String? = null

    override suspend fun startScan(
        requestJson: String,
        sessionId: String,
    ) {
        startScanEntered?.complete(Unit)
        releaseStartScan?.await()
        faults.next(DiagnosticsBridgeFaultTarget.START_SCAN)?.throwOrIgnore()
        startedRequestJson = requestJson
        startedSessionId = sessionId
        afterStartScan()
        if (autoCompleteOnStart) {
            val request = json.decodeFromString(EngineScanRequestWire.serializer(), requestJson)
            progressJson =
                json.encodeToString(
                    com.poyka.ripdpi.diagnostics.contract.engine.EngineProgressWire
                        .serializer(),
                    ScanProgress(
                        sessionId = sessionId,
                        phase = "complete",
                        completedSteps = 1,
                        totalSteps = 1,
                        message = "done",
                        isFinished = true,
                    ).toEngineProgressWire(),
                )
            reportJson =
                json.encodeToString(
                    com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                        .serializer(),
                    ScanReport(
                        sessionId = sessionId,
                        profileId = request.profileId,
                        pathMode = request.pathMode,
                        startedAt = 10L,
                        finishedAt = 20L,
                        summary = "Finished",
                        results =
                            listOf(
                                ProbeResult(
                                    probeType = "dns",
                                    target = "blocked.example",
                                    outcome = "substituted",
                                ),
                            ),
                    ).toEngineScanReportWire(),
                )
            passiveEventsPayloads.clear()
            passiveEventsPayloads.addLast(
                json.encodeToString(
                    ListSerializer(NativeSessionEvent.serializer()),
                    listOf(
                        NativeSessionEvent(
                            source = "native",
                            level = "info",
                            message = "scan started",
                            createdAt = 15L,
                        ),
                    ),
                ),
            )
            passiveEventsPayloads.addLast("[]")
        }
    }

    override suspend fun cancelScan() {
        faults.next(DiagnosticsBridgeFaultTarget.CANCEL)?.throwOrIgnore()
        cancelCount += 1
    }

    override suspend fun pollProgressJson(): String? {
        faults.next(DiagnosticsBridgeFaultTarget.POLL_PROGRESS)?.throwOrIgnore()
        return scriptedProgress.removeFirstOrNull().resolve(progressJson)
    }

    override suspend fun takeReportJson(): String? {
        faults.next(DiagnosticsBridgeFaultTarget.TAKE_REPORT)?.throwOrIgnore()
        return scriptedReports.removeFirstOrNull().resolve(reportJson).also {
            if (it != null) {
                lastTakenReportJson = it
            }
            if (scriptedReports.isEmpty()) {
                reportJson = null
            }
        }
    }

    override suspend fun pollPassiveEventsJson(): String? {
        faults.next(DiagnosticsBridgeFaultTarget.PASSIVE_EVENTS)?.throwOrIgnore()
        val scripted = scriptedPassiveEvents.removeFirstOrNull()
        val defaultValue =
            if (scripted == null) {
                passiveEventsPayloads.removeFirstOrNull()
            } else {
                passiveEventsPayloads.firstOrNull()
            }
        return scripted.resolve(defaultValue)
    }

    override suspend fun destroy() {
        val destroyBlock: suspend () -> Unit = {
            destroyEntered?.complete(Unit)
            releaseDestroy?.await()
            if (requireActiveContextOnDestroy) currentCoroutineContext().ensureActive()
            faults.next(DiagnosticsBridgeFaultTarget.DESTROY)?.throwOrIgnore()
            lastTakenReportJsonAtDestroy = lastTakenReportJson
            destroyCount += 1
            destroyCompleted?.complete(Unit)
        }
        if (destroyIgnoresCancellation) {
            withContext(NonCancellable) { destroyBlock() }
        } else {
            destroyBlock()
        }
    }

    fun enqueueProgress(progress: ScanProgress) {
        scriptedProgress.addLast(
            DiagnosticsBridgeStep.Payload(
                json.encodeToString(
                    com.poyka.ripdpi.diagnostics.contract.engine.EngineProgressWire
                        .serializer(),
                    progress.toEngineProgressWire(),
                ),
            ),
        )
    }

    fun enqueueProgress(value: String?) {
        scriptedProgress.addLast(DiagnosticsBridgeStep.Payload(value))
    }

    fun enqueueProgressFailure(error: Throwable) {
        scriptedProgress.addLast(DiagnosticsBridgeStep.Failure(error))
    }

    fun enqueueReport(report: ScanReport) {
        enqueueReport(
            report = report,
            disposition = com.poyka.ripdpi.diagnostics.contract.engine.ScanReportDisposition.TERMINAL,
        )
    }

    fun enqueueReport(
        report: ScanReport,
        disposition: com.poyka.ripdpi.diagnostics.contract.engine.ScanReportDisposition,
    ) {
        scriptedReports.addLast(
            DiagnosticsBridgeStep.Payload(
                json.encodeToString(
                    com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                        .serializer(),
                    report.toEngineScanReportWire().copy(reportDisposition = disposition),
                ),
            ),
        )
    }

    fun enqueueReport(value: String?) {
        scriptedReports.addLast(DiagnosticsBridgeStep.Payload(value))
    }

    fun enqueuePassiveEvents(value: String?) {
        scriptedPassiveEvents.addLast(DiagnosticsBridgeStep.Payload(value))
    }
}

internal class FakeDiagnosticsRuntimeCoordinator(
    private var inPathRouteLease: DiagnosticsInPathRouteLease? = null,
) : DiagnosticsRuntimeCoordinator {
    val rawScanCount = AtomicInteger(0)
    val automaticRawScanCount = AtomicInteger(0)
    private val scriptedLeaseValidationResults = ArrayDeque<Boolean>()

    override suspend fun runRawPathScan(block: suspend () -> Unit): RawPathExecutionResult {
        rawScanCount.incrementAndGet()
        return runSettledRawPathBlock(block)
    }

    override suspend fun runAutomaticRawPathScan(block: suspend () -> Unit): RawPathExecutionResult {
        automaticRawScanCount.incrementAndGet()
        return runSettledRawPathBlock(block)
    }

    override suspend fun acquireInPathRouteLease(): DiagnosticsInPathRouteLease? = inPathRouteLease

    override fun isInPathRouteLeaseCurrent(lease: DiagnosticsInPathRouteLease): Boolean =
        scriptedLeaseValidationResults.removeFirstOrNull() ?: (inPathRouteLease == lease)

    fun enqueueLeaseValidationResults(vararg results: Boolean) {
        scriptedLeaseValidationResults.addAll(results.toList())
    }

    fun updateInPathRouteLease(lease: DiagnosticsInPathRouteLease?) {
        inPathRouteLease = lease
    }
}

@Suppress("TooGenericExceptionCaught")
internal suspend fun runSettledRawPathBlock(block: suspend () -> Unit): RawPathExecutionResult =
    try {
        block()
        completedRawPathExecutionResult()
    } catch (failure: CancellationException) {
        val result =
            completedRawPathExecutionResult(
                executionOutcome = RawPathExecutionOutcome.BlockCancelled,
                executionFailure = failure.message,
            )
        throw RawPathExecutionCancelledException(result, failure)
    } catch (failure: Exception) {
        completedRawPathExecutionResult(
            executionOutcome = RawPathExecutionOutcome.BlockFailed,
            executionFailure = failure.message,
        )
    }

internal fun completedRawPathExecutionResult(
    executionOutcome: RawPathExecutionOutcome = RawPathExecutionOutcome.Completed,
    settlementOutcome: RawPathExecutionSettlementOutcome = RawPathExecutionSettlementOutcome.Restored,
    executionFailure: String? = null,
): RawPathExecutionResult =
    RawPathExecutionResult(
        settlement =
            RawPathExecutionSettlement(
                rawWindowGeneration = 1L,
                resumeIntentGeneration = 1L,
                outcome = settlementOutcome,
                runtimeWasRunning = true,
                resumeRequired = true,
                postRuntimeContext =
                    RawPathRuntimeContext(
                        status = RawPathRuntimeStatus.Running,
                        mode = Mode.VPN,
                    ),
            ),
        executionOutcome = executionOutcome,
        executionFailure = executionFailure,
    )

private fun DiagnosticsBridgeStep?.resolve(defaultValue: String?): String? =
    when (this) {
        null -> defaultValue
        is DiagnosticsBridgeStep.Payload -> value
        is DiagnosticsBridgeStep.Failure -> throw error
    }

private fun <T> FaultSpec<T>.throwOrIgnore() {
    when (outcome) {
        FaultOutcome.MALFORMED_PAYLOAD,
        FaultOutcome.BLANK_PAYLOAD,
        -> Unit

        else -> throw faultThrowable(outcome, message)
    }
}

internal class FakeResolverOverrideStore : ResolverOverrideStore {
    private val state = MutableStateFlow<TemporaryResolverOverride?>(null)

    override val override: StateFlow<TemporaryResolverOverride?> = state.asStateFlow()

    override fun setTemporaryOverride(override: TemporaryResolverOverride) {
        state.value = override
    }

    override fun clear() {
        state.value = null
    }
}

internal class FakeServerCapabilityStore : ServerCapabilityStore {
    private val relayRecords = linkedMapOf<String, ServerCapabilityRecord>()
    private val directPathRecords = linkedMapOf<String, ServerCapabilityRecord>()

    override suspend fun relayCapabilitiesForFingerprint(fingerprintHash: String): List<ServerCapabilityRecord> =
        relayRecords.values
            .filter {
                it.fingerprintHash == fingerprintHash
            }.sortedByDescending(ServerCapabilityRecord::updatedAt)

    override suspend fun directPathCapabilitiesForFingerprint(fingerprintHash: String): List<ServerCapabilityRecord> =
        directPathRecords.values
            .filter { it.fingerprintHash == fingerprintHash }
            .sortedByDescending(ServerCapabilityRecord::updatedAt)

    override suspend fun rememberRelayObservation(
        fingerprint: NetworkFingerprint,
        authority: String,
        relayProfileId: String?,
        observation: ServerCapabilityObservation,
        source: String,
        recordedAt: Long?,
    ): ServerCapabilityRecord =
        remember(
            records = relayRecords,
            scope = ServerCapabilityScope.Relay,
            fingerprint = fingerprint,
            authority = authority,
            relayProfileId = relayProfileId,
            observation = observation,
            source = source,
            recordedAt = recordedAt,
        )

    override suspend fun rememberDirectPathObservation(
        fingerprint: NetworkFingerprint,
        authority: String,
        observation: ServerCapabilityObservation,
        source: String,
        recordedAt: Long?,
    ): ServerCapabilityRecord =
        remember(
            records = directPathRecords,
            scope = ServerCapabilityScope.DirectPath,
            fingerprint = fingerprint,
            authority = authority,
            relayProfileId = null,
            observation = observation,
            source = source,
            recordedAt = recordedAt,
        )

    override suspend fun clearAll() {
        relayRecords.clear()
        directPathRecords.clear()
    }

    private fun remember(
        records: MutableMap<String, ServerCapabilityRecord>,
        scope: ServerCapabilityScope,
        fingerprint: NetworkFingerprint,
        authority: String,
        relayProfileId: String?,
        observation: ServerCapabilityObservation,
        source: String,
        recordedAt: Long?,
    ): ServerCapabilityRecord {
        val normalizedAuthority = authority.trim().lowercase(Locale.US)
        val key =
            listOf(
                scope.wireValue,
                fingerprint.scopeKey(),
                normalizedAuthority,
                relayProfileId.orEmpty(),
            ).joinToString(":")
        val existing = records[key]
        val merged =
            ServerCapabilityRecord(
                scope = scope.wireValue,
                fingerprintHash = fingerprint.scopeKey(),
                authority = normalizedAuthority,
                relayProfileId = relayProfileId ?: existing?.relayProfileId,
                quicUsable = observation.quicUsable ?: existing?.quicUsable,
                udpUsable = observation.udpUsable ?: existing?.udpUsable,
                authModeAccepted = observation.authModeAccepted ?: existing?.authModeAccepted,
                multiplexReusable = observation.multiplexReusable ?: existing?.multiplexReusable,
                shadowTlsCamouflageAccepted =
                    observation.shadowTlsCamouflageAccepted ?: existing?.shadowTlsCamouflageAccepted,
                naiveHttpsProxyAccepted =
                    observation.naiveHttpsProxyAccepted ?: existing?.naiveHttpsProxyAccepted,
                fallbackRequired = observation.fallbackRequired ?: existing?.fallbackRequired,
                repeatedHandshakeFailureClass =
                    observation.repeatedHandshakeFailureClass ?: existing?.repeatedHandshakeFailureClass,
                source = source,
                updatedAt = recordedAt ?: System.currentTimeMillis(),
            )
        records[key] = merged
        return merged
    }
}

internal class FakePolicyHandoverEventStore : PolicyHandoverEventStore {
    private val state = MutableSharedFlow<PolicyHandoverEvent>(extraBufferCapacity = 8)

    override val events: SharedFlow<PolicyHandoverEvent> = state.asSharedFlow()

    val acknowledged = mutableListOf<String>()
    var pendingOverride: Boolean? = null

    override suspend fun publish(event: PolicyHandoverEvent) = state.emit(event)

    override suspend fun acknowledge(deliveryId: String) {
        acknowledged += deliveryId
    }

    override suspend fun isPending(deliveryId: String): Boolean = pendingOverride ?: (deliveryId !in acknowledged)
}

internal class FakeServiceStateStore(
    initialStatus: Pair<AppStatus, Mode> = AppStatus.Halted to Mode.VPN,
) : OrderedServiceStateStore {
    private val statusState = MutableStateFlow(initialStatus)
    private val eventFlow = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 1)
    private val telemetryState = MutableStateFlow(ServiceTelemetrySnapshot())
    private val historyEventChannel = Channel<ServiceHistoryEvent>(Channel.UNLIMITED)

    override val status: StateFlow<Pair<AppStatus, Mode>> = statusState.asStateFlow()
    override val events: SharedFlow<ServiceEvent> = eventFlow.asSharedFlow()
    override val historyEvents: Flow<ServiceHistoryEvent> = historyEventChannel.receiveAsFlow()
    override val telemetry: StateFlow<ServiceTelemetrySnapshot> = telemetryState.asStateFlow()

    init {
        check(
            historyEventChannel
                .trySend(
                    ServiceHistoryEvent.StatusChanged(initialStatus.first, initialStatus.second),
                ).isSuccess,
        )
    }

    override fun setStatus(
        status: AppStatus,
        mode: Mode,
    ) {
        val previousStatus = statusState.value
        statusState.value = status to mode
        if (previousStatus != status to mode) {
            check(historyEventChannel.trySend(ServiceHistoryEvent.StatusChanged(status, mode)).isSuccess)
        }
        val now = System.currentTimeMillis()
        val currentTelemetry = telemetryState.value
        telemetryState.value =
            currentTelemetry.copy(
                mode = mode,
                status = status,
                serviceStartedAt =
                    when {
                        status == AppStatus.Running && currentTelemetry.status != AppStatus.Running -> now
                        status == AppStatus.Running -> currentTelemetry.serviceStartedAt
                        else -> null
                    },
                restartCount =
                    when {
                        status == AppStatus.Running && currentTelemetry.status != AppStatus.Running -> {
                            currentTelemetry.restartCount + 1
                        }

                        else -> {
                            currentTelemetry.restartCount
                        }
                    },
                updatedAt = now,
            )
    }

    override fun emitFailed(
        sender: Sender,
        reason: FailureReason,
    ) {
        val now = System.currentTimeMillis()
        telemetryState.value =
            telemetryState.value.copy(
                lastFailureSender = sender,
                lastFailureAt = now,
                updatedAt = now,
            )
        val event = ServiceEvent.Failed(sender, reason, statusState.value.first, statusState.value.second)
        eventFlow.tryEmit(event)
        check(historyEventChannel.trySend(ServiceHistoryEvent.Failed(event)).isSuccess)
    }

    override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) {
        val currentTelemetry = telemetryState.value
        telemetryState.value =
            snapshot.copy(
                serviceStartedAt = snapshot.serviceStartedAt ?: currentTelemetry.serviceStartedAt,
                restartCount = maxOf(snapshot.restartCount, currentTelemetry.restartCount),
                lastFailureSender = snapshot.lastFailureSender ?: currentTelemetry.lastFailureSender,
                lastFailureAt = snapshot.lastFailureAt ?: currentTelemetry.lastFailureAt,
            )
    }
}

internal fun defaultDiagnosticsAppSettings(): AppSettings =
    AppSettings
        .newBuilder()
        .setProxyIp("127.0.0.1")
        .setProxyPort(1080)
        .setDiagnosticsMonitorEnabled(true)
        .setDiagnosticsSampleIntervalSeconds(15)
        .setDiagnosticsDefaultScanPathMode("raw_path")
        .setDiagnosticsAutoResumeAfterRawScan(true)
        .setDiagnosticsActiveProfileId("default")
        .setDiagnosticsHistoryRetentionDays(14)
        .setDiagnosticsExportIncludeHistory(true)
        .build()

internal fun diagnosticsTestJson(): Json =
    Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

internal fun networkSnapshotModelForTest(): NetworkSnapshotModel =
    NetworkSnapshotModel(
        transport = "wifi",
        capabilities = listOf("validated"),
        dnsServers = listOf("1.1.1.1"),
        privateDnsMode = "system",
        mtu = 1500,
        localAddresses = listOf("192.0.2.10"),
        publicIp = "198.51.100.8",
        publicAsn = "AS64500",
        captivePortalDetected = false,
        networkValidated = true,
        wifiDetails =
            WifiNetworkDetails(
                ssid = "RIPDPI Lab",
                bssid = "aa:bb:cc:dd:ee:ff",
                frequencyMhz = 5180,
                band = "5 GHz",
                channelWidth = "80 MHz",
                wifiStandard = "802.11ax",
                rssiDbm = -53,
                linkSpeedMbps = 866,
                rxLinkSpeedMbps = 780,
                txLinkSpeedMbps = 720,
                hiddenSsid = false,
                networkId = 7,
                isPasspoint = false,
                isOsuAp = false,
                gateway = "192.0.2.1",
                dhcpServer = "192.0.2.2",
                ipAddress = "192.0.2.10",
                subnetMask = "255.255.255.0",
                leaseDurationSeconds = 3600,
            ),
        capturedAt = 123L,
    )

internal fun diagnosticsSession(
    id: String,
    profileId: String,
    pathMode: String,
    summary: String,
    status: String = "completed",
    reportJson: String? =
        Json.encodeToString(
            com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                .serializer(),
            ScanReport(
                sessionId = id,
                profileId = profileId,
                pathMode = ScanPathMode.valueOf(pathMode),
                startedAt = 10L,
                finishedAt = 20L,
                summary = summary,
                results = emptyList(),
            ).toEngineScanReportWire(),
        ),
): ScanSessionEntity =
    ScanSessionEntity(
        id = id,
        profileId = profileId,
        pathMode = pathMode,
        serviceMode = "VPN",
        status = status,
        summary = summary,
        reportJson = reportJson,
        startedAt = 10L,
        finishedAt = if (status == "completed") 20L else null,
    )

internal data class DiagnosticsProfileTargets(
    val domainTargets: List<DomainTarget> = emptyList(),
    val dnsTargets: List<DnsTarget> = emptyList(),
    val tcpTargets: List<TcpTarget> = emptyList(),
    val quicTargets: List<QuicTarget> = emptyList(),
    val serviceTargets: List<ServiceTarget> = emptyList(),
    val circumventionTargets: List<CircumventionTarget> = emptyList(),
    val throughputTargets: List<ThroughputTarget> = emptyList(),
    val whitelistSni: List<String> = emptyList(),
    val telegramTarget: TelegramTarget? = null,
    val strategyProbe: StrategyProbeRequest? = null,
)

internal fun diagnosticsProfileRequestJson(
    json: Json,
    profileId: String,
    displayName: String,
    kind: ScanKind = ScanKind.CONNECTIVITY,
    family: DiagnosticProfileFamily = DiagnosticProfileFamily.GENERAL,
    intentBucket: DiagnosticsProfileIntentBucket = DiagnosticsProfileIntentBucket.SAFE_DEFAULT,
    legalSafety: DiagnosticsLegalSafety = DiagnosticsLegalSafety.SAFE,
    targets: DiagnosticsProfileTargets = DiagnosticsProfileTargets(),
    allowBackground: Boolean = false,
    requiresRawPath: Boolean = kind == ScanKind.STRATEGY_PROBE,
    manualOnly: Boolean = false,
    probePersistencePolicy: ProbePersistencePolicyWire =
        if (allowBackground) {
            ProbePersistencePolicyWire.BACKGROUND_ONLY
        } else {
            ProbePersistencePolicyWire.MANUAL_ONLY
        },
): String =
    json.encodeToString(
        ProfileSpecWire.serializer(),
        ProfileSpecWire(
            profileId = profileId,
            displayName = displayName,
            kind = kind,
            family = family,
            intentBucket = intentBucket,
            legalSafety = legalSafety,
            executionPolicy =
                ProfileExecutionPolicyWire(
                    manualOnly = manualOnly,
                    allowBackground = allowBackground,
                    requiresRawPath = requiresRawPath,
                    probePersistencePolicy = probePersistencePolicy,
                ),
            domainTargets = targets.domainTargets,
            dnsTargets = targets.dnsTargets,
            tcpTargets = targets.tcpTargets,
            quicTargets = targets.quicTargets,
            serviceTargets = targets.serviceTargets,
            circumventionTargets = targets.circumventionTargets,
            throughputTargets = targets.throughputTargets,
            whitelistSni = targets.whitelistSni,
            telegramTarget = targets.telegramTarget,
            strategyProbe = targets.strategyProbe,
        ),
    )

private fun <T, K> List<T>.upsertById(
    item: T,
    keySelector: (T) -> K,
): List<T> {
    val key = keySelector(item)
    val remaining = filterNot { keySelector(it) == key }
    return remaining + item
}

private fun List<NativeSessionEventEntity>.toBoundedNativeEventArchiveSource(
    newestLimit: Int,
    criticalClassLimit: Int,
): DiagnosticsNativeEventArchiveSource {
    val sourceEvents = distinctBy(NativeSessionEventEntity::id)
    val newest = sourceEvents.sortedByDescending(NativeSessionEventEntity::createdAt).take(newestLimit)
    val critical =
        DiagnosticsNativeEventArchiveClass.entries
            .filterNot { eventClass -> eventClass == DiagnosticsNativeEventArchiveClass.OTHER }
            .flatMap { eventClass ->
                sourceEvents
                    .filter { event -> event.archiveEventClass() == eventClass }
                    .sortedByDescending(NativeSessionEventEntity::createdAt)
                    .take(criticalClassLimit)
            }
    return DiagnosticsNativeEventArchiveSource(
        events = (newest + critical).distinctBy(NativeSessionEventEntity::id),
        sourceCounts = sourceEvents.archiveEventClassCounts(),
    )
}

internal object NoopNetworkEdgePreferenceStore : NetworkEdgePreferenceStore {
    override suspend fun getPreferredEdges(
        fingerprintHash: String,
        host: String,
        transportKind: String,
    ): List<PreferredEdgeCandidate> = emptyList()

    override suspend fun getPreferredEdgesForRuntime(
        fingerprintHash: String,
    ): Map<String, List<PreferredEdgeCandidate>> = emptyMap()

    override suspend fun clearAll() = Unit

    override suspend fun rememberPreferredEdges(
        fingerprint: NetworkFingerprint,
        host: String,
        transportKind: String,
        edges: List<PreferredEdgeCandidate>,
        recordedAt: Long?,
    ): NetworkEdgePreferenceEntity = error("unused")

    override suspend fun recordEdgeResult(
        fingerprint: NetworkFingerprint,
        host: String,
        transportKind: String,
        ip: String,
        success: Boolean,
        recordedAt: Long?,
        echCapable: Boolean,
        cdnProvider: String?,
    ): NetworkEdgePreferenceEntity = error("unused")
}
