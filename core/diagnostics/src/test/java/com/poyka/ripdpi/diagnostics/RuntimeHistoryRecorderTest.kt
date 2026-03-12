package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.NativeRuntimeEvent
import com.poyka.ripdpi.core.NativeRuntimeSnapshot
import com.poyka.ripdpi.core.TunnelStats
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.data.diagnostics.BypassUsageSessionEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
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
import com.poyka.ripdpi.services.DefaultServiceStateStore
import com.poyka.ripdpi.services.FailureReason
import com.poyka.ripdpi.services.ServiceTelemetrySnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeHistoryRecorderTest {
    @Test
    fun `failure without active session creates failed connection history`() =
        runTest {
            val history = InMemoryDiagnosticsHistoryRepository()
            val serviceStateStore = DefaultServiceStateStore()
            val recorder =
                DefaultRuntimeHistoryRecorder(
                    appSettingsRepository = RecorderFakeAppSettingsRepository(),
                    historyRepository = history,
                    networkMetadataProvider = RecorderFakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = RecorderFakeDiagnosticsContextProvider(),
                    serviceStateStore = serviceStateStore,
                )

            recorder.start()
            Thread.sleep(100)
            serviceStateStore.emitFailed(Sender.Proxy, FailureReason.NativeError("boom"))

            waitUntil { history.usageSessionsState.value.isNotEmpty() && history.nativeEventsState.value.isNotEmpty() }

            val session = history.usageSessionsState.value.single()
            val event = history.nativeEventsState.value.single()

            assertEquals("Failed", session.connectionState)
            assertEquals("boom", session.failureMessage)
            assertNotNull(session.finishedAt)
            assertEquals(session.id, event.connectionSessionId)
            assertEquals("proxy", event.source)
            assertEquals("error", event.level)
        }

    @Test
    fun `running session records sampled telemetry and deduplicated runtime events`() =
        runTest {
            val history = InMemoryDiagnosticsHistoryRepository()
            val serviceStateStore = DefaultServiceStateStore()
            val recorder =
                DefaultRuntimeHistoryRecorder(
                    appSettingsRepository =
                        RecorderFakeAppSettingsRepository(
                            defaultAppSettings()
                                .toBuilder()
                                .setDiagnosticsSampleIntervalSeconds(5)
                                .build(),
                        ),
                    historyRepository = history,
                    networkMetadataProvider = RecorderFakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = RecorderFakeDiagnosticsContextProvider(),
                    serviceStateStore = serviceStateStore,
                )

            recorder.start()
            serviceStateStore.setStatus(AppStatus.Running, Mode.VPN)
            serviceStateStore.updateTelemetry(
                ServiceTelemetrySnapshot(
                    mode = Mode.VPN,
                    status = AppStatus.Running,
                    tunnelStats = TunnelStats(txPackets = 4, txBytes = 1_024, rxPackets = 5, rxBytes = 2_048),
                    proxyTelemetry =
                        NativeRuntimeSnapshot(
                            source = "proxy",
                            state = "running",
                            health = "healthy",
                            routeChanges = 2,
                            nativeEvents =
                                listOf(
                                    NativeRuntimeEvent(
                                        source = "proxy",
                                        level = "info",
                                        message = "accepted",
                                        createdAt = 100L,
                                    ),
                                ),
                        ),
                    tunnelTelemetry =
                        NativeRuntimeSnapshot(
                            source = "tunnel",
                            state = "running",
                            health = "healthy",
                            resolverId = "cloudflare",
                            resolverProtocol = "doh",
                            resolverEndpoint = "https://cloudflare-dns.com/dns-query",
                            resolverLatencyMs = 38,
                            dnsFailuresTotal = 2,
                            resolverFallbackActive = true,
                            resolverFallbackReason = "UDP DNS showed dns_substitution",
                            networkHandoverClass = "transport_switch",
                        ),
                    serviceStartedAt = System.currentTimeMillis(),
                    restartCount = 1,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            serviceStateStore.updateTelemetry(
                serviceStateStore.telemetry.value.copy(
                    proxyTelemetry =
                        serviceStateStore.telemetry.value.proxyTelemetry.copy(
                            nativeEvents =
                                listOf(
                                    NativeRuntimeEvent(
                                        source = "proxy",
                                        level = "info",
                                        message = "accepted",
                                        createdAt = 100L,
                                    ),
                                ),
                        ),
                    updatedAt = System.currentTimeMillis(),
                ),
            )

            waitUntil(timeoutMillis = 8_000) {
                history.telemetryState.value.isNotEmpty() &&
                    history.snapshotsState.value.isNotEmpty() &&
                    history.contextsState.value.isNotEmpty()
            }

            val session = history.usageSessionsState.value.single()
            val telemetrySample = history.telemetryState.value.single()
            assertEquals("Running", session.connectionState)
            assertEquals("VPN", session.serviceMode)
            assertEquals(1_024L, session.txBytes)
            assertEquals(2_048L, session.rxBytes)
            assertEquals("cloudflare", telemetrySample.resolverId)
            assertEquals("doh", telemetrySample.resolverProtocol)
            assertEquals("https://cloudflare-dns.com/dns-query", telemetrySample.resolverEndpoint)
            assertEquals(38L, telemetrySample.resolverLatencyMs)
            assertEquals(2, telemetrySample.dnsFailuresTotal)
            assertTrue(telemetrySample.resolverFallbackActive)
            assertEquals("UDP DNS showed dns_substitution", telemetrySample.resolverFallbackReason)
            assertEquals("transport_switch", telemetrySample.networkHandoverClass)
            assertEquals(1, history.nativeEventsState.value.size)
            assertTrue(history.snapshotsState.value.all { it.connectionSessionId == session.id })
            assertTrue(history.contextsState.value.all { it.connectionSessionId == session.id })
            assertTrue(history.telemetryState.value.all { it.connectionSessionId == session.id })

            serviceStateStore.setStatus(AppStatus.Halted, Mode.VPN)
            waitUntil { history.usageSessionsState.value.single().finishedAt != null }
            assertFalse(history.usageSessionsState.value.single().finishedAt == null)
        }
}

private class RecorderFakeAppSettingsRepository(
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

private fun defaultAppSettings(): AppSettings =
    AppSettings
        .newBuilder()
        .setRipdpiMode("vpn")
        .setProxyIp("127.0.0.1")
        .setProxyPort(1080)
        .setDiagnosticsMonitorEnabled(true)
        .setDiagnosticsSampleIntervalSeconds(15)
        .setDiagnosticsActiveProfileId("default")
        .setDiagnosticsHistoryRetentionDays(14)
        .setDiagnosticsExportIncludeHistory(true)
        .build()

private class InMemoryDiagnosticsHistoryRepository : DiagnosticsHistoryRepository {
    val profilesState = MutableStateFlow<List<DiagnosticProfileEntity>>(emptyList())
    val sessionsState = MutableStateFlow<List<ScanSessionEntity>>(emptyList())
    val snapshotsState = MutableStateFlow<List<NetworkSnapshotEntity>>(emptyList())
    val contextsState = MutableStateFlow<List<DiagnosticContextEntity>>(emptyList())
    val telemetryState = MutableStateFlow<List<TelemetrySampleEntity>>(emptyList())
    val nativeEventsState = MutableStateFlow<List<NativeSessionEventEntity>>(emptyList())
    val exportsState = MutableStateFlow<List<ExportRecordEntity>>(emptyList())
    val usageSessionsState = MutableStateFlow<List<BypassUsageSessionEntity>>(emptyList())

    override fun observeProfiles(): Flow<List<DiagnosticProfileEntity>> = profilesState

    override fun observeRecentScanSessions(limit: Int): Flow<List<ScanSessionEntity>> = sessionsState

    override fun observeSnapshots(limit: Int): Flow<List<NetworkSnapshotEntity>> = snapshotsState

    override fun observeConnectionSnapshots(
        connectionSessionId: String,
        limit: Int,
    ): Flow<List<NetworkSnapshotEntity>> =
        MutableStateFlow(snapshotsState.value.filter { it.connectionSessionId == connectionSessionId }.take(limit))

    override fun observeContexts(limit: Int): Flow<List<DiagnosticContextEntity>> = contextsState

    override fun observeConnectionContexts(
        connectionSessionId: String,
        limit: Int,
    ): Flow<List<DiagnosticContextEntity>> =
        MutableStateFlow(contextsState.value.filter { it.connectionSessionId == connectionSessionId }.take(limit))

    override fun observeTelemetry(limit: Int): Flow<List<TelemetrySampleEntity>> = telemetryState

    override fun observeConnectionTelemetry(
        connectionSessionId: String,
        limit: Int,
    ): Flow<List<TelemetrySampleEntity>> =
        MutableStateFlow(telemetryState.value.filter { it.connectionSessionId == connectionSessionId }.take(limit))

    override fun observeNativeEvents(limit: Int): Flow<List<NativeSessionEventEntity>> = nativeEventsState

    override fun observeConnectionNativeEvents(
        connectionSessionId: String,
        limit: Int,
    ): Flow<List<NativeSessionEventEntity>> =
        MutableStateFlow(nativeEventsState.value.filter { it.connectionSessionId == connectionSessionId }.take(limit))

    override fun observeExportRecords(limit: Int): Flow<List<ExportRecordEntity>> = exportsState

    override fun observeBypassUsageSessions(limit: Int): Flow<List<BypassUsageSessionEntity>> = usageSessionsState

    override suspend fun getProfile(id: String): DiagnosticProfileEntity? = profilesState.value.firstOrNull { it.id == id }

    override suspend fun getPackVersion(packId: String): TargetPackVersionEntity? = null

    override suspend fun getScanSession(sessionId: String): ScanSessionEntity? = sessionsState.value.firstOrNull { it.id == sessionId }

    override suspend fun getBypassUsageSession(sessionId: String): BypassUsageSessionEntity? =
        usageSessionsState.value.firstOrNull { it.id == sessionId }

    override suspend fun getProbeResults(sessionId: String): List<ProbeResultEntity> = emptyList()

    override suspend fun upsertProfile(profile: DiagnosticProfileEntity) {
        profilesState.value = profilesState.value.filterNot { it.id == profile.id } + profile
    }

    override suspend fun upsertPackVersion(version: TargetPackVersionEntity) = Unit

    override suspend fun upsertScanSession(session: ScanSessionEntity) {
        sessionsState.value = sessionsState.value.filterNot { it.id == session.id } + session
    }

    override suspend fun replaceProbeResults(
        sessionId: String,
        results: List<ProbeResultEntity>,
    ) = Unit

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
        usageSessionsState.value = usageSessionsState.value.filterNot { it.id == session.id } + session
    }

    override suspend fun trimOldData(retentionDays: Int) = Unit
}

private class RecorderFakeNetworkMetadataProvider : NetworkMetadataProvider {
    override suspend fun captureSnapshot(): NetworkSnapshotModel =
        NetworkSnapshotModel(
            transport = "wifi",
            capabilities = listOf("validated"),
            dnsServers = listOf("1.1.1.1"),
            privateDnsMode = "system",
            mtu = 1_500,
            localAddresses = listOf("192.0.2.10"),
            publicIp = "198.51.100.8",
            publicAsn = "AS64500",
            captivePortalDetected = false,
            networkValidated = true,
            capturedAt = System.currentTimeMillis(),
        )
}

private class RecorderFakeDiagnosticsContextProvider : DiagnosticsContextProvider {
    override suspend fun captureContext(): DiagnosticContextModel =
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
                    chainSummary = "tcp: split",
                    routeGroup = "1",
                    sessionUptimeMs = 1_000L,
                    lastNativeErrorHeadline = "none",
                    restartCount = 1,
                    hostAutolearnEnabled = "disabled",
                    learnedHostCount = 0,
                    penalizedHostCount = 0,
                    lastAutolearnHost = "",
                    lastAutolearnGroup = "",
                    lastAutolearnAction = "",
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
                    appVersionCode = 1,
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

private fun waitUntil(
    timeoutMillis: Long = 2_000,
    predicate: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (predicate()) {
            return
        }
        Thread.sleep(25)
    }
    assertTrue("Timed out waiting for condition", predicate())
}
