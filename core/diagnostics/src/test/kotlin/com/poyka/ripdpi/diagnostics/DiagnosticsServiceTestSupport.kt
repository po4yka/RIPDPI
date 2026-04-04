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
import com.poyka.ripdpi.data.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.data.EncryptedDnsPathCandidate
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkFingerprint
import com.poyka.ripdpi.data.NetworkFingerprintProvider
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.PolicyHandoverEventStore
import com.poyka.ripdpi.data.ResolverOverrideStore
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TemporaryResolverOverride
import com.poyka.ripdpi.data.WifiNetworkIdentityTuple
import com.poyka.ripdpi.data.diagnostics.BypassUsageHistoryStore
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactReadStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsArtifactWriteStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryClock
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRetentionStore
import com.poyka.ripdpi.data.diagnostics.DiagnosticsProfileCatalog
import com.poyka.ripdpi.data.diagnostics.DiagnosticsScanRecordStore
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceEntity
import com.poyka.ripdpi.data.diagnostics.NetworkDnsPathPreferenceRecordStore
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyEntity
import com.poyka.ripdpi.data.diagnostics.RememberedNetworkPolicyRecordStore
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TargetPackVersionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProbePersistencePolicyWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileExecutionPolicyWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileSpecWire
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
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
    DiagnosticsArtifactWriteStore,
    BypassUsageHistoryStore,
    RememberedNetworkPolicyRecordStore,
    NetworkDnsPathPreferenceRecordStore,
    DiagnosticsHistoryRetentionStore {
    val profilesState = MutableStateFlow<List<DiagnosticProfileEntity>>(emptyList())
    val sessionsState = MutableStateFlow<List<ScanSessionEntity>>(emptyList())
    val snapshotsState = MutableStateFlow<List<NetworkSnapshotEntity>>(emptyList())
    val contextsState = MutableStateFlow<List<DiagnosticContextEntity>>(emptyList())
    val telemetryState = MutableStateFlow<List<TelemetrySampleEntity>>(emptyList())
    val nativeEventsState = MutableStateFlow<List<NativeSessionEventEntity>>(emptyList())
    val exportsState = MutableStateFlow<List<ExportRecordEntity>>(emptyList())
    val usageSessionsState = MutableStateFlow<List<BypassUsageSessionEntity>>(emptyList())
    val rememberedPoliciesState = MutableStateFlow<List<RememberedNetworkPolicyEntity>>(emptyList())
    val networkDnsPathPreferencesState = MutableStateFlow<List<NetworkDnsPathPreferenceEntity>>(emptyList())
    var currentTime: Long = Long.MAX_VALUE
    private val packVersions = mutableMapOf<String, TargetPackVersionEntity>()
    private val probeResults = mutableMapOf<String, List<ProbeResultEntity>>()

    override fun observeProfiles(): Flow<List<DiagnosticProfileEntity>> = profilesState

    override fun observeRecentScanSessions(limit: Int): Flow<List<ScanSessionEntity>> = sessionsState

    override fun observeSnapshots(limit: Int): Flow<List<NetworkSnapshotEntity>> = snapshotsState

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

    override fun observeContexts(limit: Int): Flow<List<DiagnosticContextEntity>> = contextsState

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

    override fun observeConnectionTelemetry(
        connectionSessionId: String,
        limit: Int,
    ): Flow<List<TelemetrySampleEntity>> =
        telemetryState.map { telemetry ->
            telemetry.filter { it.connectionSessionId == connectionSessionId }.take(limit)
        }

    override fun observeNativeEvents(limit: Int): Flow<List<NativeSessionEventEntity>> = nativeEventsState

    override suspend fun getNativeEventsForSession(
        sessionId: String,
        limit: Int,
    ): List<NativeSessionEventEntity> = nativeEventsState.value.filter { it.sessionId == sessionId }.take(limit)

    override fun observeConnectionNativeEvents(
        connectionSessionId: String,
        limit: Int,
    ): Flow<List<NativeSessionEventEntity>> =
        nativeEventsState.map { events ->
            events.filter { it.connectionSessionId == connectionSessionId }.take(limit)
        }

    override fun observeExportRecords(limit: Int): Flow<List<ExportRecordEntity>> = exportsState

    override fun observeBypassUsageSessions(limit: Int): Flow<List<BypassUsageSessionEntity>> = usageSessionsState

    override fun observeRememberedNetworkPolicies(limit: Int): Flow<List<RememberedNetworkPolicyEntity>> =
        rememberedPoliciesState

    override suspend fun getProfile(id: String): DiagnosticProfileEntity? = profilesState.value.find { it.id == id }

    override suspend fun getPackVersion(packId: String): TargetPackVersionEntity? = packVersions[packId]

    override suspend fun getScanSession(sessionId: String): ScanSessionEntity? =
        sessionsState.value.find { it.id == sessionId }

    override suspend fun getBypassUsageSession(sessionId: String): BypassUsageSessionEntity? =
        usageSessionsState.value.find { it.id == sessionId }

    override suspend fun getRememberedNetworkPolicy(
        fingerprintHash: String,
        mode: String,
    ): RememberedNetworkPolicyEntity? =
        rememberedPoliciesState.value.find { it.fingerprintHash == fingerprintHash && it.mode == mode }

    override suspend fun getNetworkDnsPathPreference(fingerprintHash: String): NetworkDnsPathPreferenceEntity? =
        networkDnsPathPreferencesState.value.find { it.fingerprintHash == fingerprintHash }

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
    }

    override suspend fun replaceProbeResults(
        sessionId: String,
        results: List<ProbeResultEntity>,
    ) {
        probeResults[sessionId] = results
    }

    override suspend fun upsertSnapshot(snapshot: NetworkSnapshotEntity) {
        snapshotsState.value = snapshotsState.value + snapshot
    }

    override suspend fun upsertContextSnapshot(snapshot: DiagnosticContextEntity) {
        contextsState.value = contextsState.value + snapshot
    }

    override suspend fun insertTelemetrySample(sample: TelemetrySampleEntity) {
        telemetryState.value = telemetryState.value + sample
    }

    override suspend fun insertNativeSessionEvent(event: NativeSessionEventEntity) {
        nativeEventsState.value = nativeEventsState.value + event
    }

    override suspend fun insertExportRecord(record: ExportRecordEntity) {
        exportsState.value = exportsState.value + record
    }

    override suspend fun upsertBypassUsageSession(session: BypassUsageSessionEntity) {
        usageSessionsState.value = usageSessionsState.value.upsertById(session) { it.id }
    }

    override suspend fun upsertRememberedNetworkPolicy(policy: RememberedNetworkPolicyEntity): Long {
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

    override suspend fun clearRememberedNetworkPolicies() {
        rememberedPoliciesState.value = emptyList()
    }

    override suspend fun clearNetworkDnsPathPreferences() {
        networkDnsPathPreferencesState.value = emptyList()
    }

    override suspend fun pruneRememberedNetworkPolicies() = Unit

    override suspend fun pruneNetworkDnsPathPreferences() = Unit

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
                            domainTargets = listOf(DomainTarget(host = "example.org")),
                            dnsTargets = listOf(DnsTarget(domain = "blocked.example")),
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
                            domainTargets = listOf(DomainTarget(host = "example.org")),
                            quicTargets = listOf(QuicTarget(host = "example.org")),
                            strategyProbe = StrategyProbeRequest(suiteId = suiteId),
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

internal class FakeDiagnosticsContextProvider : DiagnosticsContextProvider {
    override suspend fun captureContext(): DiagnosticContextModel = captureContextForTest()

    fun captureContextForTest(): DiagnosticContextModel =
        DiagnosticContextModel(
            service =
                ServiceContextModel(
                    serviceStatus = "Running",
                    configuredMode = "VPN",
                    activeMode = "VPN",
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

    override fun create(): NetworkDiagnosticsBridge = bridge
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
    var autoCompleteOnStart: Boolean = true
    var cancelCount: Int = 0
    var destroyCount: Int = 0
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
        faults.next(DiagnosticsBridgeFaultTarget.START_SCAN)?.throwOrIgnore()
        startedRequestJson = requestJson
        if (autoCompleteOnStart) {
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
                        profileId = "default",
                        pathMode =
                            json
                                .decodeFromString(
                                    EngineScanRequestWire.serializer(),
                                    requestJson,
                                ).pathMode,
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
        faults.next(DiagnosticsBridgeFaultTarget.DESTROY)?.throwOrIgnore()
        destroyCount += 1
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
        scriptedReports.addLast(
            DiagnosticsBridgeStep.Payload(
                json.encodeToString(
                    com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
                        .serializer(),
                    report.toEngineScanReportWire(),
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

internal class FakeDiagnosticsRuntimeCoordinator : DiagnosticsRuntimeCoordinator {
    val rawScanCount = AtomicInteger(0)
    val automaticRawScanCount = AtomicInteger(0)

    override suspend fun runRawPathScan(block: suspend () -> Unit) {
        rawScanCount.incrementAndGet()
        block()
    }

    override suspend fun runAutomaticRawPathScan(block: suspend () -> Unit) {
        automaticRawScanCount.incrementAndGet()
        block()
    }
}

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

internal class FakePolicyHandoverEventStore : PolicyHandoverEventStore {
    private val state = MutableSharedFlow<PolicyHandoverEvent>(extraBufferCapacity = 8)

    override val events: SharedFlow<PolicyHandoverEvent> = state.asSharedFlow()

    override fun publish(event: PolicyHandoverEvent) {
        state.tryEmit(event)
    }
}

internal class FakeServiceStateStore(
    initialStatus: Pair<AppStatus, Mode> = AppStatus.Halted to Mode.VPN,
) : ServiceStateStore {
    private val statusState = MutableStateFlow(initialStatus)
    private val eventFlow = MutableSharedFlow<ServiceEvent>(extraBufferCapacity = 1)
    private val telemetryState = MutableStateFlow(ServiceTelemetrySnapshot())

    override val status: StateFlow<Pair<AppStatus, Mode>> = statusState.asStateFlow()
    override val events: SharedFlow<ServiceEvent> = eventFlow.asSharedFlow()
    override val telemetry: StateFlow<ServiceTelemetrySnapshot> = telemetryState.asStateFlow()

    override fun setStatus(
        status: AppStatus,
        mode: Mode,
    ) {
        statusState.value = status to mode
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
        eventFlow.tryEmit(ServiceEvent.Failed(sender, reason))
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

internal fun diagnosticsProfileRequestJson(
    json: Json,
    profileId: String,
    displayName: String,
    kind: ScanKind = ScanKind.CONNECTIVITY,
    family: DiagnosticProfileFamily = DiagnosticProfileFamily.GENERAL,
    domainTargets: List<DomainTarget> = emptyList(),
    dnsTargets: List<DnsTarget> = emptyList(),
    tcpTargets: List<TcpTarget> = emptyList(),
    quicTargets: List<QuicTarget> = emptyList(),
    serviceTargets: List<ServiceTarget> = emptyList(),
    circumventionTargets: List<CircumventionTarget> = emptyList(),
    throughputTargets: List<ThroughputTarget> = emptyList(),
    whitelistSni: List<String> = emptyList(),
    telegramTarget: TelegramTarget? = null,
    strategyProbe: StrategyProbeRequest? = null,
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
            executionPolicy =
                ProfileExecutionPolicyWire(
                    manualOnly = manualOnly,
                    allowBackground = allowBackground,
                    requiresRawPath = requiresRawPath,
                    probePersistencePolicy = probePersistencePolicy,
                ),
            domainTargets = domainTargets,
            dnsTargets = dnsTargets,
            tcpTargets = tcpTargets,
            quicTargets = quicTargets,
            serviceTargets = serviceTargets,
            circumventionTargets = circumventionTargets,
            throughputTargets = throughputTargets,
            whitelistSni = whitelistSni,
            telegramTarget = telegramTarget,
            strategyProbe = strategyProbe,
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
