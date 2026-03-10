package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.DiagnosticsManager
import com.poyka.ripdpi.diagnostics.ExportBundle
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `ui state mirrors diagnostics telemetry and event streams`() =
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
                progressState.value =
                    ScanProgress(
                        sessionId = "session-1",
                        phase = "dns",
                        completedSteps = 1,
                        totalSteps = 2,
                        message = "Checking DNS",
                    )
                sessionsState.value =
                    listOf(
                        ScanSessionEntity(
                            id = "session-1",
                            profileId = "default",
                            pathMode = "RAW_PATH",
                            serviceMode = "VPN",
                            status = "running",
                            summary = "Running",
                            reportJson = null,
                            startedAt = 1L,
                            finishedAt = null,
                        ),
                    )
                snapshotsState.value =
                    listOf(
                        NetworkSnapshotEntity(
                            id = "snapshot-1",
                            sessionId = "session-1",
                            snapshotKind = "passive",
                            payloadJson = "{}",
                            capturedAt = 10L,
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
                            publicIp = "198.51.100.7",
                            txPackets = 1,
                            txBytes = 2,
                            rxPackets = 3,
                            rxBytes = 4,
                            createdAt = 20L,
                        ),
                    )
                nativeEventsState.value =
                    listOf(
                        NativeSessionEventEntity(
                            id = "event-1",
                            sessionId = null,
                            source = "proxy",
                            level = "info",
                            message = "accepted",
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
            assertEquals("Default", state.activeProfile?.name)
            assertEquals("Checking DNS", state.activeScanMessage)
            assertEquals(1, state.telemetry.size)
            assertEquals(1, state.events.size)
            assertEquals(1, state.exports.size)

            collector.cancel()
        }

    @Test
    fun `exportLatest uses first session in current diagnostics history`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    sessionsState.value =
                        listOf(
                            ScanSessionEntity(
                                id = "session-latest",
                                profileId = "default",
                                pathMode = "IN_PATH",
                                serviceMode = "Proxy",
                                status = "completed",
                                summary = "Latest",
                                reportJson = "{}",
                                startedAt = 2L,
                                finishedAt = 3L,
                            ),
                            ScanSessionEntity(
                                id = "session-older",
                                profileId = "default",
                                pathMode = "RAW_PATH",
                                serviceMode = "VPN",
                                status = "completed",
                                summary = "Older",
                                reportJson = "{}",
                                startedAt = 1L,
                                finishedAt = 2L,
                            ),
                        )
                }
            val viewModel = DiagnosticsViewModel(manager)
            var exportedPath: String? = null
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }

            advanceUntilIdle()
            viewModel.exportLatest { exportedPath = it }
            advanceUntilIdle()

            assertEquals("session-latest", manager.lastExportSessionId)
            assertEquals("/tmp/export-session-latest.zip", exportedPath)

            collector.cancel()
        }
}

private class FakeDiagnosticsManager : DiagnosticsManager {
    private val _progressState = MutableStateFlow<ScanProgress?>(null)
    val progressState: MutableStateFlow<ScanProgress?> = _progressState
    val profilesState = MutableStateFlow<List<DiagnosticProfileEntity>>(emptyList())
    val sessionsState = MutableStateFlow<List<ScanSessionEntity>>(emptyList())
    val snapshotsState = MutableStateFlow<List<NetworkSnapshotEntity>>(emptyList())
    val telemetryState = MutableStateFlow<List<TelemetrySampleEntity>>(emptyList())
    val nativeEventsState = MutableStateFlow<List<NativeSessionEventEntity>>(emptyList())
    val exportsState = MutableStateFlow<List<ExportRecordEntity>>(emptyList())
    var initializeCalls = 0
    var lastExportSessionId: String? = null

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

    override suspend fun exportBundle(sessionId: String?): ExportBundle {
        lastExportSessionId = sessionId
        return ExportBundle(
            fileName = "export.zip",
            absolutePath = "/tmp/export-${sessionId ?: "all"}.zip",
        )
    }
}
