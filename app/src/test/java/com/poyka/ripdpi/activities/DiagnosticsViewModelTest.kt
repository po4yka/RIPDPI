package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.DiagnosticsArchive
import com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticsManager
import com.poyka.ripdpi.diagnostics.NetworkSnapshotModel
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.diagnostics.ScanReport
import com.poyka.ripdpi.diagnostics.ShareSummary
import com.poyka.ripdpi.diagnostics.SummaryMetric
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val json = Json

    @Test
    fun `ui state groups overview live sessions and share models`() =
        runTest {
            val manager = FakeDiagnosticsManager().apply {
                profilesState.value =
                    listOf(
                        DiagnosticProfileEntity(
                            id = "default",
                            name = "Default",
                            source = "bundled",
                            version = 1,
                            requestJson = "{}",
                            updatedAt = 1L,
                        ),
                    )
                sessionsState.value =
                    listOf(
                        session(
                            id = "session-1",
                            profileId = "default",
                            pathMode = "IN_PATH",
                            summary = "Latest report",
                        ),
                    )
                snapshotsState.value =
                    listOf(
                        snapshot(
                            id = "snapshot-1",
                            sessionId = "session-1",
                        ),
                    )
                telemetryState.value =
                    listOf(
                        TelemetrySampleEntity(
                            id = "telemetry-1",
                            sessionId = null,
                            activeMode = "VPN",
                            connectionState = "Running",
                            networkType = "wifi",
                            publicIp = "198.51.100.8",
                            txPackets = 3,
                            txBytes = 4_000,
                            rxPackets = 5,
                            rxBytes = 6_000,
                            createdAt = 20L,
                        ),
                    )
                nativeEventsState.value =
                    listOf(
                        NativeSessionEventEntity(
                            id = "event-1",
                            sessionId = "session-1",
                            source = "proxy",
                            level = "warn",
                            message = "Route advanced",
                            createdAt = 30L,
                        ),
                    )
                exportsState.value =
                    listOf(
                        ExportRecordEntity(
                            id = "export-1",
                            sessionId = "session-1",
                            uri = "/tmp/report.zip",
                            fileName = "report.zip",
                            createdAt = 40L,
                        ),
                    )
            }

            val viewModel = DiagnosticsViewModel(manager)
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(1, manager.initializeCalls)
            assertEquals(DiagnosticsSection.Overview, state.selectedSection)
            assertEquals("Default", state.overview.activeProfile?.name)
            assertEquals("Running", state.live.statusLabel)
            assertEquals(1, state.sessions.sessions.size)
            assertEquals("report.zip", state.share.latestArchiveFileName)
            assertTrue(state.events.events.first().severity.contains("WARN"))
            collector.cancel()
        }

    @Test
    fun `active scan forces scan section and profile selection updates manager`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    profilesState.value =
                        listOf(
                            DiagnosticProfileEntity(
                                id = "default",
                                name = "Default",
                                source = "bundled",
                                version = 1,
                                requestJson = "{}",
                                updatedAt = 1L,
                            ),
                            DiagnosticProfileEntity(
                                id = "custom",
                                name = "Custom",
                                source = "local",
                                version = 1,
                                requestJson = "{}",
                                updatedAt = 2L,
                            ),
                        )
                    progressState.value =
                        ScanProgress(
                            sessionId = "session-running",
                            phase = "dns",
                            completedSteps = 1,
                            totalSteps = 3,
                            message = "Checking DNS",
                        )
                }

            val viewModel = DiagnosticsViewModel(manager)
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.selectSection(DiagnosticsSection.Events)
            viewModel.selectProfile("custom")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(DiagnosticsSection.Scan, state.selectedSection)
            assertEquals("custom", manager.lastActiveProfileId)
            assertEquals("custom", state.scan.selectedProfileId)
            assertNotNull(state.scan.activeProgress)
            collector.cancel()
        }

    @Test
    fun `select session loads grouped detail model`() =
        runTest {
            val detail =
                DiagnosticSessionDetail(
                    session = session(id = "session-1", profileId = "default", pathMode = "RAW_PATH", summary = "Session"),
                    results =
                        listOf(
                            ProbeResultEntity(
                                id = "probe-1",
                                sessionId = "session-1",
                                probeType = "dns",
                                target = "example.org",
                                outcome = "blocked",
                                detailJson = json.encodeToString(listOf(ProbeDetail("resolver", "1.1.1.1"))),
                                createdAt = 1L,
                            ),
                        ),
                    snapshots = listOf(snapshot(id = "snapshot-1", sessionId = "session-1")),
                    events =
                        listOf(
                            NativeSessionEventEntity(
                                id = "event-1",
                                sessionId = "session-1",
                                source = "proxy",
                                level = "info",
                                message = "accepted",
                                createdAt = 2L,
                            ),
                        ),
                )
            val manager = FakeDiagnosticsManager(detail = detail)
            val viewModel = DiagnosticsViewModel(manager)
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.selectSession("session-1")
            advanceUntilIdle()

            val selected = viewModel.uiState.value.selectedSessionDetail
            assertNotNull(selected)
            assertEquals("Session", selected?.session?.title)
            assertEquals(1, selected?.probeGroups?.first()?.items?.size)
            assertEquals("example.org", selected?.probeGroups?.first()?.items?.first()?.target)
            collector.cancel()
        }

    @Test
    fun `share summary emits effect and archive actions use selected target session`() =
        runTest {
            val manager = FakeDiagnosticsManager().apply {
                sessionsState.value =
                    listOf(
                        session(
                            id = "session-1",
                            profileId = "default",
                            pathMode = "RAW_PATH",
                            summary = "Selected",
                        ),
                    )
            }
            val viewModel = DiagnosticsViewModel(manager)
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val shareEffect = async { viewModel.effects.first() }
            viewModel.shareSummary("session-1")
            advanceUntilIdle()

            val effect = shareEffect.await() as DiagnosticsEffect.ShareSummaryRequested
            assertEquals("RIPDPI summary", effect.title)
            assertTrue(effect.body.contains("session-1"))

            val shareArchiveEffect = async { viewModel.effects.first() }
            viewModel.shareArchive("session-1")
            advanceUntilIdle()

            val shareArchive = shareArchiveEffect.await() as DiagnosticsEffect.ShareArchiveRequested
            assertEquals("session-1", manager.lastArchiveSessionId)
            assertEquals("/tmp/archive-session-1.zip", shareArchive.absolutePath)

            val saveArchiveEffect = async { viewModel.effects.first() }
            viewModel.saveArchive("session-1")
            advanceUntilIdle()

            val saveArchive = saveArchiveEffect.await() as DiagnosticsEffect.SaveArchiveRequested
            assertEquals("session-1", manager.lastArchiveSessionId)
            assertEquals("/tmp/archive-session-1.zip", saveArchive.absolutePath)
            collector.cancel()
        }

    @Test
    fun `event filter narrows the visible stream`() =
        runTest {
            val manager = FakeDiagnosticsManager().apply {
                nativeEventsState.value =
                    listOf(
                        NativeSessionEventEntity(
                            id = "event-1",
                            sessionId = null,
                            source = "proxy",
                            level = "warn",
                            message = "First warning",
                            createdAt = 1L,
                        ),
                        NativeSessionEventEntity(
                            id = "event-2",
                            sessionId = null,
                            source = "tunnel",
                            level = "info",
                            message = "Healthy session",
                            createdAt = 2L,
                        ),
                    )
            }
            val viewModel = DiagnosticsViewModel(manager)
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.toggleEventFilter(source = "Proxy")
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.events.events.size)
            assertEquals("Proxy", viewModel.uiState.value.events.events.first().source)
            collector.cancel()
        }

    @Test
    fun `archive failure updates share state`() =
        runTest {
            val manager =
                FakeDiagnosticsManager(
                    archiveFailure = IllegalStateException("boom"),
                ).apply {
                    sessionsState.value =
                        listOf(
                            session(
                                id = "session-1",
                                profileId = "default",
                                pathMode = "RAW_PATH",
                                summary = "Selected",
                            ),
                        )
                }
            val viewModel = DiagnosticsViewModel(manager)
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.shareArchive("session-1")
            advanceUntilIdle()

            val shareState = viewModel.uiState.value.share
            assertEquals("Failed to generate archive", shareState.archiveStateMessage)
            assertEquals(DiagnosticsTone.Negative, shareState.archiveStateTone)
            assertFalse(shareState.isArchiveBusy)
            collector.cancel()
        }

    private fun session(
        id: String,
        profileId: String,
        pathMode: String,
        summary: String,
    ): ScanSessionEntity =
        ScanSessionEntity(
            id = id,
            profileId = profileId,
            pathMode = pathMode,
            serviceMode = "VPN",
            status = "completed",
            summary = summary,
            reportJson =
                json.encodeToString(
                    ScanReport(
                        sessionId = id,
                        profileId = profileId,
                        pathMode = ScanPathMode.valueOf(pathMode),
                        startedAt = 1L,
                        finishedAt = 2L,
                        summary = summary,
                        results =
                            listOf(
                                com.poyka.ripdpi.diagnostics.ProbeResult(
                                    probeType = "dns",
                                    target = "example.org",
                                    outcome = "ok",
                                    details = listOf(ProbeDetail("resolver", "1.1.1.1")),
                                ),
                            ),
                    ),
                ),
            startedAt = 1L,
            finishedAt = 2L,
        )

    private fun snapshot(
        id: String,
        sessionId: String?,
    ): NetworkSnapshotEntity =
        NetworkSnapshotEntity(
            id = id,
            sessionId = sessionId,
            snapshotKind = "passive",
            payloadJson =
                json.encodeToString(
                    NetworkSnapshotModel(
                        transport = "wifi",
                        capabilities = listOf("validated"),
                        dnsServers = listOf("1.1.1.1"),
                        privateDnsMode = "strict",
                        mtu = 1500,
                        localAddresses = listOf("192.168.1.4"),
                        publicIp = "198.51.100.8",
                        publicAsn = "AS64500",
                        captivePortalDetected = false,
                        networkValidated = true,
                        capturedAt = 10L,
                    ),
                ),
            capturedAt = 10L,
        )
}

private class FakeDiagnosticsManager(
    private val detail: DiagnosticSessionDetail? = null,
    private val archiveFailure: Throwable? = null,
) : DiagnosticsManager {
    private val _progressState = MutableStateFlow<ScanProgress?>(null)
    val progressState: MutableStateFlow<ScanProgress?> = _progressState
    val profilesState = MutableStateFlow<List<DiagnosticProfileEntity>>(emptyList())
    val sessionsState = MutableStateFlow<List<ScanSessionEntity>>(emptyList())
    val snapshotsState = MutableStateFlow<List<NetworkSnapshotEntity>>(emptyList())
    val telemetryState = MutableStateFlow<List<TelemetrySampleEntity>>(emptyList())
    val nativeEventsState = MutableStateFlow<List<NativeSessionEventEntity>>(emptyList())
    val exportsState = MutableStateFlow<List<ExportRecordEntity>>(emptyList())
    var initializeCalls = 0
    var lastArchiveSessionId: String? = null
    var lastActiveProfileId: String? = null

    override val activeScanProgress: StateFlow<ScanProgress?> = _progressState.asStateFlow()
    override val profiles: Flow<List<DiagnosticProfileEntity>> = profilesState
    override val sessions: Flow<List<ScanSessionEntity>> = sessionsState
    override val snapshots: Flow<List<NetworkSnapshotEntity>> = snapshotsState
    override val telemetry: Flow<List<TelemetrySampleEntity>> = telemetryState
    override val nativeEvents: Flow<List<NativeSessionEventEntity>> = nativeEventsState
    override val exports: Flow<List<ExportRecordEntity>> = exportsState

    override suspend fun initialize() {
        initializeCalls += 1
    }

    override suspend fun startScan(pathMode: ScanPathMode): String = "session-${pathMode.name}"

    override suspend fun cancelActiveScan() {
        progressState.value = null
    }

    override suspend fun setActiveProfile(profileId: String) {
        lastActiveProfileId = profileId
    }

    override suspend fun loadSessionDetail(sessionId: String): DiagnosticSessionDetail =
        requireNotNull(detail) { "Missing fake detail for $sessionId" }

    override suspend fun buildShareSummary(sessionId: String?): ShareSummary =
        ShareSummary(
            title = "RIPDPI summary",
            body = "Summary for ${sessionId ?: "latest"}",
            compactMetrics = listOf(SummaryMetric("Path", "RAW_PATH")),
        )

    override suspend fun createArchive(sessionId: String?): DiagnosticsArchive {
        archiveFailure?.let { throw it }
        lastArchiveSessionId = sessionId
        return DiagnosticsArchive(
            fileName = "archive.zip",
            absolutePath = "/tmp/archive-${sessionId ?: "all"}.zip",
            sessionId = sessionId,
            createdAt = 42L,
            scope = "hybrid",
            schemaVersion = 2,
            privacyMode = "split_output",
        )
    }
}
