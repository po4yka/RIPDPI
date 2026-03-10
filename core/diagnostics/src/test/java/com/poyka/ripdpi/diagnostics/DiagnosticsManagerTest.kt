package com.poyka.ripdpi.diagnostics

import android.content.ContextWrapper
import com.poyka.ripdpi.core.NativeRuntimeEvent
import com.poyka.ripdpi.core.NetworkDiagnosticsBridge
import com.poyka.ripdpi.core.NetworkDiagnosticsBridgeFactory
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryRepository
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TargetPackVersionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.services.DiagnosticsRuntimeCoordinator
import com.poyka.ripdpi.services.ServiceEvent
import com.poyka.ripdpi.services.ServiceStateStore
import com.poyka.ripdpi.services.ServiceTelemetrySnapshot
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsManagerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `raw path scan persists completed report and bridged telemetry events`() =
        runTest {
            val history = FakeDiagnosticsHistoryRepository().apply { seedDefaultProfile(json) }
            val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json)
            val runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator()
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy)
            val manager =
                DefaultDiagnosticsManager(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    historyRepository = history,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = runtimeCoordinator,
                    serviceStateStore = serviceStateStore,
                )

            val sessionId = manager.startScan(ScanPathMode.RAW_PATH)
            waitForCompletion(history, sessionId)

            val request = json.decodeFromString(ScanRequest.serializer(), bridgeFactory.bridge.startedRequestJson!!)
            val session = history.getScanSession(sessionId)
            val results = history.getProbeResults(sessionId)

            assertEquals(1, runtimeCoordinator.rawScanCount.get())
            assertEquals(ScanPathMode.RAW_PATH, request.pathMode)
            assertNull(request.proxyHost)
            assertNull(request.proxyPort)
            assertEquals("completed", session?.status)
            assertEquals(ScanPathMode.RAW_PATH.name, session?.pathMode)
            assertEquals(2, history.snapshotsState.value.size)
            assertEquals(1, results.size)
            assertEquals(2, history.nativeEventsState.value.size)
            assertTrue(history.nativeEventsState.value.any { it.source == "native" })
            assertTrue(history.nativeEventsState.value.any { it.source == "dns" })
        }

    @Test
    fun `in path scan injects proxy settings into request`() =
        runTest {
            val history = FakeDiagnosticsHistoryRepository().apply { seedDefaultProfile(json) }
            val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json)
            val runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator()
            val settings = defaultAppSettings().toBuilder().setProxyIp("10.0.0.2").setProxyPort(2080).build()
            val manager =
                DefaultDiagnosticsManager(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(settings),
                    historyRepository = history,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = runtimeCoordinator,
                    serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
                )

            val sessionId = manager.startScan(ScanPathMode.IN_PATH)
            waitForCompletion(history, sessionId)

            val request = json.decodeFromString(ScanRequest.serializer(), bridgeFactory.bridge.startedRequestJson!!)

            assertEquals(0, runtimeCoordinator.rawScanCount.get())
            assertEquals(ScanPathMode.IN_PATH, request.pathMode)
            assertEquals("10.0.0.2", request.proxyHost)
            assertEquals(2080, request.proxyPort)
        }

    @Test
    fun `persistServiceNativeEvents stores proxy and tunnel runtime events`() =
        runTest {
            val history = FakeDiagnosticsHistoryRepository()
            val manager =
                DefaultDiagnosticsManager(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    historyRepository = history,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    networkDiagnosticsBridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json),
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                )

            manager.persistServiceNativeEvents(
                ServiceTelemetrySnapshot(
                    proxyTelemetry =
                        com.poyka.ripdpi.core.NativeRuntimeSnapshot(
                            source = "proxy",
                            nativeEvents =
                                listOf(
                                    NativeRuntimeEvent(
                                        source = "proxy",
                                        level = "info",
                                        message = "accepted",
                                        createdAt = 10L,
                                    ),
                                ),
                        ),
                    tunnelTelemetry =
                        com.poyka.ripdpi.core.NativeRuntimeSnapshot(
                            source = "tunnel",
                            nativeEvents =
                                listOf(
                                    NativeRuntimeEvent(
                                        source = "tunnel",
                                        level = "warn",
                                        message = "slow upstream",
                                        createdAt = 20L,
                                    ),
                                ),
                        ),
                ),
            )

            assertEquals(2, history.nativeEventsState.value.size)
            assertEquals(setOf("proxy", "tunnel"), history.nativeEventsState.value.map { it.source }.toSet())
        }

    private suspend fun waitForCompletion(
        history: FakeDiagnosticsHistoryRepository,
        sessionId: String,
    ) {
        withTimeout(2_000) {
            while (
                history.getScanSession(sessionId)?.status != "completed" ||
                history.snapshotsState.value.size < 2 ||
                history.nativeEventsState.value.size < 2
            ) {
                delay(25)
            }
        }
    }
}

private class FakeAppSettingsRepository(
    initialSettings: AppSettings = defaultAppSettings(),
) : AppSettingsRepository {
    private val state = MutableStateFlow(initialSettings)

    override val settings: Flow<AppSettings> = state

    override suspend fun snapshot(): AppSettings = state.value

    override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
        state.value = state.value.toBuilder().apply(transform).build()
    }

    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}

private class TestContext : ContextWrapper(null)

private class FakeDiagnosticsHistoryRepository : DiagnosticsHistoryRepository {
    val profilesState = MutableStateFlow<List<DiagnosticProfileEntity>>(emptyList())
    val sessionsState = MutableStateFlow<List<ScanSessionEntity>>(emptyList())
    val snapshotsState = MutableStateFlow<List<NetworkSnapshotEntity>>(emptyList())
    val telemetryState = MutableStateFlow<List<TelemetrySampleEntity>>(emptyList())
    val nativeEventsState = MutableStateFlow<List<NativeSessionEventEntity>>(emptyList())
    val exportsState = MutableStateFlow<List<ExportRecordEntity>>(emptyList())
    private val packVersions = mutableMapOf<String, TargetPackVersionEntity>()
    private val probeResults = mutableMapOf<String, List<ProbeResultEntity>>()

    override fun observeProfiles(): Flow<List<DiagnosticProfileEntity>> = profilesState

    override fun observeRecentScanSessions(limit: Int): Flow<List<ScanSessionEntity>> = sessionsState

    override fun observeSnapshots(limit: Int): Flow<List<NetworkSnapshotEntity>> = snapshotsState

    override fun observeTelemetry(limit: Int): Flow<List<TelemetrySampleEntity>> = telemetryState

    override fun observeNativeEvents(limit: Int): Flow<List<NativeSessionEventEntity>> = nativeEventsState

    override fun observeExportRecords(limit: Int): Flow<List<ExportRecordEntity>> = exportsState

    override suspend fun getProfile(id: String): DiagnosticProfileEntity? = profilesState.value.find { it.id == id }

    override suspend fun getPackVersion(packId: String): TargetPackVersionEntity? = packVersions[packId]

    override suspend fun getScanSession(sessionId: String): ScanSessionEntity? =
        sessionsState.value.find { it.id == sessionId }

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

    override suspend fun insertTelemetrySample(sample: TelemetrySampleEntity) {
        telemetryState.value = telemetryState.value + sample
    }

    override suspend fun insertNativeSessionEvent(event: NativeSessionEventEntity) {
        nativeEventsState.value = nativeEventsState.value + event
    }

    override suspend fun insertExportRecord(record: ExportRecordEntity) {
        exportsState.value = exportsState.value + record
    }

    override suspend fun trimOldData(retentionDays: Int) = Unit

    fun seedDefaultProfile(json: Json) {
        profilesState.value =
            listOf(
                DiagnosticProfileEntity(
                    id = "default",
                    name = "Default",
                    source = "bundled",
                    version = 1,
                    requestJson =
                        json.encodeToString(
                            ScanRequest.serializer(),
                            ScanRequest(
                                profileId = "default",
                                displayName = "Default",
                                pathMode = ScanPathMode.RAW_PATH,
                                domainTargets = listOf(DomainTarget(host = "example.org")),
                                dnsTargets = listOf(DnsTarget(domain = "blocked.example")),
                            ),
                        ),
                    updatedAt = 1L,
                ),
            )
    }
}

private class FakeNetworkMetadataProvider : NetworkMetadataProvider {
    override suspend fun captureSnapshot(): NetworkSnapshotModel =
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
            capturedAt = 123L,
        )
}

private class FakeNetworkDiagnosticsBridgeFactory(
    private val json: Json,
) : NetworkDiagnosticsBridgeFactory {
    val bridge = FakeNetworkDiagnosticsBridge(json)

    override fun create(): NetworkDiagnosticsBridge = bridge
}

private class FakeNetworkDiagnosticsBridge(
    private val json: Json,
) : NetworkDiagnosticsBridge {
    var startedRequestJson: String? = null
    private var startedSessionId: String? = null
    private val passiveEventsPayloads = ArrayDeque<String>()
    private var reportJson: String? = null
    private var progressJson: String? = null

    override suspend fun startScan(
        requestJson: String,
        sessionId: String,
    ) {
        startedRequestJson = requestJson
        startedSessionId = sessionId
        progressJson =
            json.encodeToString(
                ScanProgress.serializer(),
                ScanProgress(
                    sessionId = sessionId,
                    phase = "complete",
                    completedSteps = 1,
                    totalSteps = 1,
                    message = "done",
                    isFinished = true,
                ),
            )
        reportJson =
            json.encodeToString(
                ScanReport.serializer(),
                ScanReport(
                    sessionId = sessionId,
                    profileId = "default",
                    pathMode =
                        json.decodeFromString(ScanRequest.serializer(), requestJson).pathMode,
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
                ),
            )
        passiveEventsPayloads.clear()
        passiveEventsPayloads.addLast(
            json.encodeToString(
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

    override suspend fun cancelScan() = Unit

    override suspend fun pollProgressJson(): String? = progressJson

    override suspend fun takeReportJson(): String? = reportJson.also { reportJson = null }

    override suspend fun pollPassiveEventsJson(): String? = passiveEventsPayloads.removeFirstOrNull()

    override suspend fun destroy() {
        startedSessionId = null
    }
}

private class FakeDiagnosticsRuntimeCoordinator : DiagnosticsRuntimeCoordinator {
    val rawScanCount = AtomicInteger(0)

    override suspend fun runRawPathScan(block: suspend () -> Unit) {
        rawScanCount.incrementAndGet()
        block()
    }
}

private class FakeServiceStateStore(
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
    }

    override fun emitFailed(sender: Sender) {
        eventFlow.tryEmit(ServiceEvent.Failed(sender))
    }

    override fun updateTelemetry(snapshot: ServiceTelemetrySnapshot) {
        telemetryState.value = snapshot
    }
}

private fun <T, K> List<T>.upsertById(
    item: T,
    keySelector: (T) -> K,
): List<T> {
    val key = keySelector(item)
    val remaining = filterNot { keySelector(it) == key }
    return remaining + item
}

private fun defaultAppSettings(): AppSettings =
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
