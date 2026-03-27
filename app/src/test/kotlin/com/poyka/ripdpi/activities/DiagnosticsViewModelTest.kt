package com.poyka.ripdpi.activities

import com.poyka.ripdpi.core.RipDpiChainConfig
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiQuicConfig
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.HttpFakeProfileCloudflareGet
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.TlsFakeProfileGoogleChrome
import com.poyka.ripdpi.data.TunnelStats
import com.poyka.ripdpi.data.UdpFakeProfileDnsQuery
import com.poyka.ripdpi.diagnostics.BypassApproachDetail
import com.poyka.ripdpi.diagnostics.BypassApproachId
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.BypassOutcomeBreakdown
import com.poyka.ripdpi.diagnostics.BypassRuntimeHealthSummary
import com.poyka.ripdpi.diagnostics.BypassStrategySignature
import com.poyka.ripdpi.diagnostics.CellularNetworkDetails
import com.poyka.ripdpi.diagnostics.DeviceContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticConnectionSession
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticProfileFamily
import com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticsArchive
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanResolution
import com.poyka.ripdpi.diagnostics.DiagnosticsManualScanStartResult
import com.poyka.ripdpi.diagnostics.EnvironmentContextModel
import com.poyka.ripdpi.diagnostics.HiddenProbeConflictAction
import com.poyka.ripdpi.diagnostics.NetworkSnapshotModel
import com.poyka.ripdpi.diagnostics.PermissionContextModel
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.ResolverRecommendation
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.diagnostics.ServiceContextModel
import com.poyka.ripdpi.diagnostics.ShareSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditAssessment
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditConfidence
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditConfidenceLevel
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditCoverage
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeRecommendation
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.diagnostics.StrategyProbeRequest
import com.poyka.ripdpi.diagnostics.SummaryMetric
import com.poyka.ripdpi.diagnostics.WifiNetworkDetails
import com.poyka.ripdpi.diagnostics.contract.engine.EngineProbeResultWire
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanReportWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProbePersistencePolicyWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileExecutionPolicyWire
import com.poyka.ripdpi.diagnostics.contract.profile.ProfileSpecWire
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import com.poyka.ripdpi.diagnostics.DiagnosticContextSnapshot as DiagnosticContextEntity
import com.poyka.ripdpi.diagnostics.DiagnosticEvent as NativeSessionEventEntity
import com.poyka.ripdpi.diagnostics.DiagnosticExportRecord as ExportRecordEntity
import com.poyka.ripdpi.diagnostics.DiagnosticNetworkSnapshot as NetworkSnapshotEntity
import com.poyka.ripdpi.diagnostics.DiagnosticProfile as DiagnosticProfileEntity
import com.poyka.ripdpi.diagnostics.DiagnosticScanSession as ScanSessionEntity
import com.poyka.ripdpi.diagnostics.DiagnosticTelemetrySample as TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.ProbeResult as ProbeResultEntity

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DiagnosticsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val json = Json

    @Test
    fun `initialize is explicit and idempotent`() =
        runTest {
            val manager = FakeDiagnosticsManager()
            val viewModel =
                createDiagnosticsViewModel(
                    appContext = RuntimeEnvironment.getApplication(),
                    diagnosticsManager = manager,
                    appSettingsRepository = FakeAppSettingsRepository(),
                    initialize = false,
                )

            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            assertEquals(0, manager.initializeCalls)

            viewModel.initialize()
            viewModel.initialize()
            advanceUntilIdle()

            assertEquals(1, manager.initializeCalls)
            collector.cancel()
        }

    @Test
    fun `ui state groups overview live sessions and share models`() =
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
                                requestJson = profileRequest(profileId = "default", displayName = "Default"),
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
                    activeConnectionSessionState.value = connectionSession(id = "connection-1")
                    telemetryState.value =
                        listOf(
                            telemetry(
                                id = "telemetry-1",
                                connectionSessionId = "connection-1",
                                lastFailureClass = "dns_tampering",
                                lastFallbackAction = "resolver_override_recommended",
                                txPackets = 3,
                                txBytes = 4_000,
                                rxPackets = 5,
                                rxBytes = 6_000,
                                createdAt = 20L,
                            ),
                        )
                    liveTelemetryState.value = telemetryState.value
                    contextsState.value =
                        listOf(
                            context(
                                id = "context-1",
                                sessionId = null,
                            ),
                        )
                    liveContextsState.value =
                        listOf(
                            context(
                                id = "live-context-1",
                                sessionId = null,
                                connectionSessionId = "connection-1",
                            ),
                        )
                    liveSnapshotsState.value =
                        listOf(
                            snapshot(
                                id = "live-snapshot-1",
                                sessionId = null,
                                connectionSessionId = "connection-1",
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
                    liveNativeEventsState.value =
                        listOf(
                            NativeSessionEventEntity(
                                id = "event-live-1",
                                sessionId = null,
                                connectionSessionId = "connection-1",
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

            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(DiagnosticsSection.Overview, state.selectedSection)
            assertEquals("Default", state.overview.activeProfile?.name)
            assertEquals("Running", state.live.statusLabel)
            assertTrue(state.live.metrics.any { it.label == "Latest native failure" && it.value == "dns_tampering" })
            assertTrue(
                state.live.metrics.any {
                    it.label == "Fallback action" && it.value == "resolver_override_recommended"
                },
            )
            assertEquals(1, state.sessions.sessions.size)
            assertEquals("report.zip", state.share.latestArchiveFileName)
            assertEquals("Device", state.overview.contextSummary?.title)
            assertTrue(
                state.overview.contextSummary
                    ?.fields
                    ?.any { it.label == "Host learning" && it.value.contains("Active") } == true,
            )
            assertTrue(
                state.events.events
                    .first()
                    .severity
                    .contains("WARN"),
            )
            collector.cancel()
        }

    @Test
    fun `live state stays standby without an active connection session`() =
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
                                requestJson = profileRequest(profileId = "default", displayName = "Default"),
                                updatedAt = 1L,
                            ),
                        )
                    sessionsState.value =
                        listOf(
                            session(
                                id = "session-failed",
                                profileId = "default",
                                pathMode = "RAW_PATH",
                                summary = "Failed report",
                                reportJson =
                                    json.encodeToString(
                                        scanReport(
                                            id = "session-failed",
                                            profileId = "default",
                                            summary = "Failed report",
                                            probes =
                                                listOf(
                                                    ProbeResultEntity(
                                                        probeType = "tcp_fat_header",
                                                        target = "rutor.info",
                                                        outcome = "whitelist_sni_failed",
                                                    ),
                                                ),
                                        ),
                                    ),
                            ),
                        )
                    telemetryState.value =
                        listOf(
                            telemetry(
                                id = "historical-telemetry",
                                connectionSessionId = "connection-old",
                                networkType = "cellular",
                                activeMode = "PROXY",
                            ),
                        )
                    nativeEventsState.value =
                        listOf(
                            NativeSessionEventEntity(
                                id = "historical-event",
                                sessionId = "session-failed",
                                source = "tcp_fat_header",
                                level = "warn",
                                message = "rutor.info: whitelist_sni_failed",
                                createdAt = 50L,
                            ),
                        )
                    snapshotsState.value =
                        listOf(
                            snapshot(
                                id = "post-scan-snapshot",
                                sessionId = "session-failed",
                                transport = "cellular",
                            ),
                        )
                }

            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(DiagnosticsHealth.Degraded, state.overview.health)
            assertEquals(DiagnosticsHealth.Idle, state.live.health)
            assertEquals("Idle", state.live.statusLabel)
            assertEquals("Live monitor standing by", state.live.headline)
            assertEquals("Runtime feed is quiet", state.live.eventSummaryLabel)
            assertTrue(state.live.passiveEvents.isEmpty())
            assertNull(state.live.snapshot)
            assertTrue(state.live.contextGroups.isEmpty())
            collector.cancel()
        }

    @Test
    fun `live state uses scoped artifacts from the active connection session`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    activeConnectionSessionState.value =
                        connectionSession(
                            id = "connection-a",
                            serviceMode = "VPN",
                            networkType = "wifi",
                        )
                    telemetryState.value =
                        listOf(
                            telemetry(
                                id = "historical-telemetry",
                                connectionSessionId = "connection-b",
                                networkType = "cellular",
                                activeMode = "PROXY",
                                txBytes = 9_000,
                                rxBytes = 12_000,
                            ),
                        )
                    nativeEventsState.value =
                        listOf(
                            NativeSessionEventEntity(
                                id = "historical-event",
                                sessionId = null,
                                connectionSessionId = "connection-b",
                                source = "proxy",
                                level = "warn",
                                message = "Historical runtime warning",
                                createdAt = 60L,
                            ),
                        )
                    liveTelemetryState.value =
                        listOf(
                            telemetry(
                                id = "live-telemetry",
                                connectionSessionId = "connection-a",
                                networkType = "wifi",
                                activeMode = "VPN",
                                txBytes = 1_000,
                                rxBytes = 2_000,
                            ),
                        )
                    liveNativeEventsState.value =
                        listOf(
                            NativeSessionEventEntity(
                                id = "live-event",
                                sessionId = null,
                                connectionSessionId = "connection-a",
                                source = "proxy",
                                level = "warn",
                                message = "Active runtime warning",
                                createdAt = 61L,
                            ),
                        )
                    liveSnapshotsState.value =
                        listOf(
                            snapshot(
                                id = "scan-snapshot",
                                sessionId = null,
                                connectionSessionId = "connection-a",
                                snapshotKind = "post_scan",
                                transport = "cellular",
                            ),
                            snapshot(
                                id = "runtime-snapshot",
                                sessionId = null,
                                connectionSessionId = "connection-a",
                                snapshotKind = "connection_sample",
                                transport = "wifi",
                            ),
                        )
                    liveContextsState.value =
                        listOf(
                            context(
                                id = "scan-context",
                                sessionId = null,
                                connectionSessionId = "connection-a",
                                contextKind = "post_scan",
                                serviceStatus = "Stopped",
                            ),
                        )
                }

            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("wifi", state.live.networkLabel)
            assertEquals("VPN", state.live.modeLabel)
            assertEquals(
                "Active runtime warning",
                state.live.passiveEvents
                    .single()
                    .message,
            )
            assertEquals("Connection sample", state.live.snapshot?.title)
            assertTrue(state.live.contextGroups.isEmpty())
            assertTrue(
                state.live.body.contains("3.0 KB transferred") || state.live.signalLabel.contains("1.0 KB sent"),
            )
            collector.cancel()
        }

    @Test
    fun `live state uses error headline for degraded active sessions without telemetry`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    activeConnectionSessionState.value =
                        connectionSession(
                            id = "connection-degraded",
                            connectionState = "Failed",
                            health = "degraded",
                            serviceMode = "VPN",
                            networkType = "wifi",
                        )
                }

            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(DiagnosticsHealth.Degraded, state.live.health)
            assertEquals("Failed", state.live.statusLabel)
            assertEquals("Runtime needs intervention", state.live.headline)
            collector.cancel()
        }

    @Test
    fun `live state falls back to active session labels when scoped telemetry is missing`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    activeConnectionSessionState.value =
                        connectionSession(
                            id = "connection-live",
                            connectionState = "Running",
                            serviceMode = "VPN",
                            networkType = "wifi",
                        )
                    telemetryState.value =
                        listOf(
                            telemetry(
                                id = "historical-telemetry",
                                connectionSessionId = "connection-old",
                                networkType = "cellular",
                                activeMode = "PROXY",
                            ),
                        )
                }

            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(DiagnosticsHealth.Idle, state.live.health)
            assertEquals("Running", state.live.statusLabel)
            assertEquals("wifi", state.live.networkLabel)
            assertEquals("VPN", state.live.modeLabel)
            assertEquals("No live telemetry", state.live.freshnessLabel)
            assertEquals("Live monitor standing by", state.live.headline)
            collector.cancel()
        }

    @Test
    fun `overview and share use current service telemetry instead of historical samples`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    activeConnectionSessionState.value =
                        connectionSession(
                            id = "connection-live",
                            connectionState = "Running",
                            serviceMode = "VPN",
                            networkType = "wifi",
                        )
                    telemetryState.value =
                        listOf(
                            telemetry(
                                id = "historical-telemetry",
                                connectionSessionId = "connection-old",
                                networkType = "cellular",
                                activeMode = "PROXY",
                                txBytes = 9_000,
                                rxBytes = 12_000,
                                createdAt = 40L,
                            ),
                        )
                }
            val serviceStateStore =
                FakeServiceStateStore().apply {
                    setStatus(AppStatus.Running, Mode.VPN)
                    updateTelemetry(
                        ServiceTelemetrySnapshot(
                            mode = Mode.VPN,
                            status = AppStatus.Running,
                            tunnelStats = TunnelStats(txPackets = 4, txBytes = 1_024, rxPackets = 5, rxBytes = 2_048),
                            updatedAt = 120L,
                        ),
                    )
                }

            val viewModel =
                createDiagnosticsViewModel(
                    appContext = RuntimeEnvironment.getApplication(),
                    diagnosticsManager = manager,
                    appSettingsRepository = FakeAppSettingsRepository(),
                    serviceStateStore = serviceStateStore,
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.overview.metrics.any { it.label == "TX" && it.value == "1.0 KB" })
            assertTrue(state.overview.metrics.any { it.label == "RX" && it.value == "2.0 KB" })
            assertFalse(state.overview.metrics.any { it.value == "8.8 KB" || it.value == "11.7 KB" })
            assertTrue(state.share.previewBody.contains("Live running"))
            assertTrue(state.share.previewBody.contains("wifi"))
            assertFalse(state.share.previewBody.contains("cellular"))
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
                                requestJson = profileRequest(profileId = "default", displayName = "Default"),
                                updatedAt = 1L,
                            ),
                            DiagnosticProfileEntity(
                                id = "custom",
                                name = "Custom",
                                source = "local",
                                version = 1,
                                requestJson = profileRequest(profileId = "default", displayName = "Default"),
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

            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.selectSection(DiagnosticsSection.Share)
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
    fun `automatic probing profile disables in path and blocks raw run in command line mode`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    profilesState.value =
                        listOf(
                            DiagnosticProfileEntity(
                                id = "automatic-probing",
                                name = "Automatic probing",
                                source = "bundled",
                                version = 1,
                                requestJson = strategyProbeProfileRequest(json),
                                updatedAt = 1L,
                            ),
                        )
                }
            val settings =
                com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setEnableCmdSettings(true)
                    .build()

            val viewModel =
                createDiagnosticsViewModel(
                    RuntimeEnvironment.getApplication(),
                    manager,
                    FakeAppSettingsRepository(settings),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val scan = viewModel.uiState.value.scan
            assertFalse(scan.runRawEnabled)
            assertFalse(scan.runInPathEnabled)
            assertTrue(scan.selectedProfileScopeLabel.orEmpty().contains("raw-path only"))
            assertNotNull(scan.runRawHint)
            assertNotNull(scan.workflowRestriction)
            assertEquals(
                DiagnosticsWorkflowRestrictionReasonUiModel.COMMAND_LINE_MODE_ACTIVE,
                scan.workflowRestriction?.reason,
            )
            assertTrue(
                scan.workflowRestriction
                    ?.body
                    .orEmpty()
                    .contains("Use command line settings", ignoreCase = true),
            )
            collector.cancel()
        }

    @Test
    fun `automatic probing profile keeps raw run enabled in visual mode`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    profilesState.value =
                        listOf(
                            DiagnosticProfileEntity(
                                id = "automatic-probing",
                                name = "Automatic probing",
                                source = "bundled",
                                version = 1,
                                requestJson = strategyProbeProfileRequest(json),
                                updatedAt = 1L,
                            ),
                        )
                }

            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val scan = viewModel.uiState.value.scan
            assertTrue(scan.runRawEnabled)
            assertFalse(scan.runInPathEnabled)
            assertTrue(scan.runRawHint.orEmpty().contains("manual recommendation", ignoreCase = true))
            assertTrue(scan.runInPathHint.orEmpty().contains("raw-path only", ignoreCase = true))
            assertNull(scan.workflowRestriction)
            collector.cancel()
        }

    @Test
    fun `connectivity profile does not expose workflow restriction in command line mode`() =
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
                                requestJson = profileRequest(profileId = "default", displayName = "Default"),
                                updatedAt = 1L,
                            ),
                        )
                }
            val settings =
                com.poyka.ripdpi.data.AppSettingsSerializer.defaultValue
                    .toBuilder()
                    .setEnableCmdSettings(true)
                    .build()

            val viewModel =
                createDiagnosticsViewModel(
                    RuntimeEnvironment.getApplication(),
                    manager,
                    FakeAppSettingsRepository(settings),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val scan = viewModel.uiState.value.scan
            assertTrue(scan.runRawEnabled)
            assertTrue(scan.runInPathEnabled)
            assertNull(scan.workflowRestriction)
            collector.cancel()
        }

    @Test
    fun `automatic probing recommendation renders in scan state`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    profilesState.value =
                        listOf(
                            DiagnosticProfileEntity(
                                id = "automatic-probing",
                                name = "Automatic probing",
                                source = "bundled",
                                version = 1,
                                requestJson = strategyProbeProfileRequest(json),
                                updatedAt = 1L,
                            ),
                        )
                    sessionsState.value =
                        listOf(
                            session(
                                id = "default-session",
                                profileId = "default",
                                pathMode = "RAW_PATH",
                                summary = "Connectivity run",
                                reportJson =
                                    json.encodeToString(
                                        EngineScanReportWire.serializer(),
                                        scanReport(
                                            id = "default-session",
                                            profileId = "default",
                                            summary = "Connectivity run",
                                            probes =
                                                listOf(
                                                    com.poyka.ripdpi.diagnostics.ProbeResult(
                                                        probeType = "dns",
                                                        target = "blocked.example",
                                                        outcome = "ok",
                                                        details = listOf(ProbeDetail("resolver", "1.1.1.1")),
                                                    ),
                                                ),
                                        ),
                                    ),
                            ),
                            session(
                                id = "probe-session",
                                profileId = "automatic-probing",
                                pathMode = "RAW_PATH",
                                summary = "Recommended hostfake",
                                reportJson =
                                    json.encodeToString(
                                        EngineScanReportWire.serializer(),
                                        strategyProbeScanReport(),
                                    ),
                            ),
                        )
                }

            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val scan = viewModel.uiState.value.scan
            val strategyProbe = scan.strategyProbeReport
            assertNotNull(strategyProbe)
            assertEquals("automatic-probing", scan.selectedProfile?.id)
            assertEquals("probe-session", scan.latestSession?.id)
            assertTrue(scan.latestResults.isEmpty())
            assertEquals("TLS record + hostfake + QUIC realistic burst", strategyProbe?.recommendation?.headline)
            assertEquals(2, strategyProbe?.families?.size)
            assertEquals("TCP candidates", strategyProbe?.families?.first()?.title)
            assertTrue(
                strategyProbe
                    ?.families
                    ?.first()
                    ?.candidates
                    ?.first()
                    ?.recommended == true,
            )
            assertTrue(
                strategyProbe
                    ?.recommendation
                    ?.signature
                    ?.any { it.label == "Chain" && it.value.contains("hostfake") } == true,
            )
            collector.cancel()
        }

    @Test
    fun `connectivity profile ignores automatic probing recommendation when selected`() =
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
                                requestJson = profileRequest(profileId = "default", displayName = "Default"),
                                updatedAt = 1L,
                            ),
                            DiagnosticProfileEntity(
                                id = "automatic-probing",
                                name = "Automatic probing",
                                source = "bundled",
                                version = 1,
                                requestJson = strategyProbeProfileRequest(json),
                                updatedAt = 2L,
                            ),
                        )
                    sessionsState.value =
                        listOf(
                            session(
                                id = "default-session",
                                profileId = "default",
                                pathMode = "IN_PATH",
                                summary = "Connectivity run",
                                reportJson =
                                    json.encodeToString(
                                        EngineScanReportWire.serializer(),
                                        scanReport(
                                            id = "default-session",
                                            profileId = "default",
                                            pathMode = ScanPathMode.IN_PATH,
                                            summary = "Connectivity run",
                                            probes =
                                                listOf(
                                                    com.poyka.ripdpi.diagnostics.ProbeResult(
                                                        probeType = "https",
                                                        target = "example.org",
                                                        outcome = "ok",
                                                        details = listOf(ProbeDetail("path", "in-path")),
                                                    ),
                                                ),
                                        ),
                                    ),
                            ),
                            session(
                                id = "probe-session",
                                profileId = "automatic-probing",
                                pathMode = "RAW_PATH",
                                summary = "Recommended hostfake",
                                reportJson =
                                    json.encodeToString(
                                        EngineScanReportWire.serializer(),
                                        strategyProbeScanReport(),
                                    ),
                            ),
                        )
                }

            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.selectProfile("default")
            advanceUntilIdle()

            val scan = viewModel.uiState.value.scan
            assertEquals("default", scan.selectedProfile?.id)
            assertEquals("default-session", scan.latestSession?.id)
            assertEquals(ScanPathMode.IN_PATH, scan.activePathMode)
            assertEquals(1, scan.latestResults.size)
            assertEquals(
                "https",
                scan.latestResults
                    .first()
                    .probeType
                    .lowercase(),
            )
            assertNull(scan.strategyProbeReport)
            collector.cancel()
        }

    @Test
    fun `resolver recommendation is surfaced in scan state and actions delegate to manager`() =
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
                                requestJson = profileRequest(profileId = "default", displayName = "Default"),
                                updatedAt = 1L,
                            ),
                        )
                    sessionsState.value =
                        listOf(
                            session(
                                id = "resolver-session",
                                profileId = "default",
                                pathMode = "IN_PATH",
                                summary = "DNS override recommended",
                                reportJson =
                                    json.encodeToString(
                                        EngineScanReportWire.serializer(),
                                        scanReport(
                                            id = "resolver-session",
                                            profileId = "default",
                                            pathMode = ScanPathMode.IN_PATH,
                                            summary = "DNS override recommended",
                                            probes =
                                                listOf(
                                                    com.poyka.ripdpi.diagnostics.ProbeResult(
                                                        probeType = "dns_integrity",
                                                        target = "blocked.example",
                                                        outcome = "dns_substitution",
                                                    ),
                                                ),
                                        ).copy(
                                            resolverRecommendation =
                                                ResolverRecommendation(
                                                    triggerOutcome = "dns_substitution",
                                                    selectedResolverId = "cloudflare",
                                                    selectedProtocol = "doh",
                                                    selectedEndpoint = "https://cloudflare-dns.com/dns-query",
                                                    selectedBootstrapIps = listOf("1.1.1.1", "1.0.0.1"),
                                                    rationale =
                                                        "Encrypted DNS stayed clean while UDP DNS was substituted.",
                                                    appliedTemporarily = true,
                                                    persistable = true,
                                                ),
                                        ),
                                    ),
                            ),
                        )
                }

            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val recommendation = viewModel.uiState.value.scan.resolverRecommendation
            assertNotNull(recommendation)
            assertEquals("Switch DNS to Cloudflare", recommendation?.headline)
            assertTrue(recommendation?.appliedTemporarily == true)
            assertTrue(recommendation?.persistable == true)
            assertTrue(recommendation?.fields?.any { it.label == "Protocol" && it.value == "DOH" } == true)

            viewModel.keepResolverRecommendationForSession()
            viewModel.saveResolverRecommendation()
            advanceUntilIdle()

            assertEquals("resolver-session", manager.keptResolverRecommendationSessionId)
            assertEquals("resolver-session", manager.savedResolverRecommendationSessionId)
            collector.cancel()
        }

    @Test
    fun `automatic probing promotes recommended candidates to the top of each family`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    profilesState.value =
                        listOf(
                            DiagnosticProfileEntity(
                                id = "automatic-probing",
                                name = "Automatic probing",
                                source = "bundled",
                                version = 1,
                                requestJson = strategyProbeProfileRequest(json),
                                updatedAt = 1L,
                            ),
                        )
                    sessionsState.value =
                        listOf(
                            session(
                                id = "probe-session",
                                profileId = "automatic-probing",
                                pathMode = "RAW_PATH",
                                summary = "Recommended split host",
                                reportJson =
                                    json.encodeToString(
                                        EngineScanReportWire.serializer(),
                                        strategyProbeScanReport(
                                            tcpCandidates =
                                                listOf(
                                                    StrategyProbeCandidateSummary(
                                                        id = "parser_only",
                                                        label = "Parser only",
                                                        family = "http",
                                                        outcome = "partial",
                                                        rationale = "Only HTTP improved",
                                                        succeededTargets = 1,
                                                        totalTargets = 3,
                                                        weightedSuccessScore = 1,
                                                        totalWeight = 5,
                                                        qualityScore = 2,
                                                        averageLatencyMs = 140,
                                                    ),
                                                    StrategyProbeCandidateSummary(
                                                        id = "tlsrec_hostfake",
                                                        label = "TLS record + hostfake",
                                                        family = "hostfake",
                                                        outcome = "success",
                                                        rationale = "Won HTTPS",
                                                        succeededTargets = 3,
                                                        totalTargets = 3,
                                                        weightedSuccessScore = 5,
                                                        totalWeight = 5,
                                                        qualityScore = 12,
                                                        averageLatencyMs = 120,
                                                    ),
                                                    StrategyProbeCandidateSummary(
                                                        id = "baseline_current",
                                                        label = "Baseline current",
                                                        family = "baseline",
                                                        outcome = "failed",
                                                        rationale = "Blocked",
                                                        succeededTargets = 0,
                                                        totalTargets = 3,
                                                        weightedSuccessScore = 0,
                                                        totalWeight = 5,
                                                        qualityScore = 0,
                                                        averageLatencyMs = null,
                                                        skipped = true,
                                                    ),
                                                ),
                                            quicCandidates =
                                                listOf(
                                                    StrategyProbeCandidateSummary(
                                                        id = "quic_disabled",
                                                        label = "QUIC disabled",
                                                        family = "quic",
                                                        outcome = "failed",
                                                        rationale = "No QUIC recovery",
                                                        succeededTargets = 0,
                                                        totalTargets = 1,
                                                        weightedSuccessScore = 0,
                                                        totalWeight = 2,
                                                        qualityScore = 0,
                                                        averageLatencyMs = null,
                                                    ),
                                                    StrategyProbeCandidateSummary(
                                                        id = "quic_realistic_burst",
                                                        label = "QUIC realistic burst",
                                                        family = "quic",
                                                        outcome = "success",
                                                        rationale = "Recovered QUIC",
                                                        succeededTargets = 1,
                                                        totalTargets = 1,
                                                        weightedSuccessScore = 2,
                                                        totalWeight = 2,
                                                        qualityScore = 4,
                                                        averageLatencyMs = 180,
                                                    ),
                                                ),
                                            recommendation =
                                                StrategyProbeRecommendation(
                                                    tcpCandidateId = "tlsrec_hostfake",
                                                    tcpCandidateLabel = "TLS record + hostfake",
                                                    quicCandidateId = "quic_realistic_burst",
                                                    quicCandidateLabel = "QUIC realistic burst",
                                                    rationale = "Won by weighted success",
                                                    recommendedProxyConfigJson =
                                                        RipDpiProxyUIPreferences()
                                                            .toNativeConfigJson(),
                                                    strategySignature = null,
                                                ),
                                        ),
                                    ),
                            ),
                        )
                }

            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val families =
                viewModel.uiState.value.scan.strategyProbeReport
                    ?.families
                    .orEmpty()
            assertEquals(
                "tlsrec_hostfake",
                families
                    .first()
                    .candidates
                    .first()
                    .id,
            )
            assertTrue(
                families
                    .first()
                    .candidates
                    .first()
                    .recommended,
            )
            assertEquals(
                "quic_realistic_burst",
                families
                    .last()
                    .candidates
                    .first()
                    .id,
            )
            assertTrue(
                families
                    .last()
                    .candidates
                    .first()
                    .recommended,
            )
            collector.cancel()
        }

    @Test
    fun `automatic audit report exposes full matrix summary and candidate detail`() =
        runTest {
            val profileId = "automatic-audit"
            val hostfakeConfigJson =
                RipDpiProxyUIPreferences(
                    chains =
                        RipDpiChainConfig(
                            tcpSteps =
                                listOf(
                                    TcpChainStepModel(kind = TcpChainStepKind.TlsRec, marker = "extlen"),
                                    TcpChainStepModel(
                                        kind = TcpChainStepKind.HostFake,
                                        marker = "endhost+8",
                                        fakeHostTemplate = "googlevideo.com",
                                    ),
                                ),
                        ),
                    quic =
                        RipDpiQuicConfig(
                            fakeProfile = "realistic_initial",
                        ),
                ).toNativeConfigJson()
            val manager =
                FakeDiagnosticsManager().apply {
                    profilesState.value =
                        listOf(
                            DiagnosticProfileEntity(
                                id = profileId,
                                name = "Automatic audit",
                                source = "bundled",
                                version = 1,
                                requestJson =
                                    strategyProbeProfileRequest(
                                        json = json,
                                        profileId = profileId,
                                        displayName = "Automatic audit",
                                        suiteId = "full_matrix_v1",
                                    ),
                                updatedAt = 1L,
                            ),
                        )
                    sessionsState.value =
                        listOf(
                            session(
                                id = "audit-session",
                                profileId = profileId,
                                pathMode = "RAW_PATH",
                                summary = "Audit complete",
                                reportJson =
                                    json.encodeToString(
                                        EngineScanReportWire.serializer(),
                                        strategyProbeScanReport(
                                            sessionId = "audit-session",
                                            profileId = profileId,
                                            suiteId = "full_matrix_v1",
                                            summary = "Audit complete",
                                            results =
                                                listOf(
                                                    com.poyka.ripdpi.diagnostics.ProbeResult(
                                                        probeType = "https",
                                                        target = "audit.example",
                                                        outcome = "ok",
                                                        details =
                                                            listOf(
                                                                ProbeDetail("candidateId", "tlsrec_hostfake"),
                                                                ProbeDetail("protocol", "https"),
                                                                ProbeDetail("latencyMs", "180"),
                                                            ),
                                                    ),
                                                ),
                                            tcpCandidates =
                                                listOf(
                                                    StrategyProbeCandidateSummary(
                                                        id = "baseline_current",
                                                        label = "Current strategy",
                                                        family = "baseline",
                                                        outcome = "failed",
                                                        rationale = "HTTPS still blocked",
                                                        succeededTargets = 0,
                                                        totalTargets = 3,
                                                        weightedSuccessScore = 0,
                                                        totalWeight = 5,
                                                        qualityScore = 0,
                                                    ),
                                                    StrategyProbeCandidateSummary(
                                                        id = "parser_only",
                                                        label = "Parser only",
                                                        family = "parser",
                                                        outcome = "partial",
                                                        rationale = "Only HTTP improved",
                                                        succeededTargets = 1,
                                                        totalTargets = 3,
                                                        weightedSuccessScore = 1,
                                                        totalWeight = 5,
                                                        qualityScore = 2,
                                                        averageLatencyMs = 140,
                                                    ),
                                                    StrategyProbeCandidateSummary(
                                                        id = "tlsrec_hostfake",
                                                        label = "TLS record + hostfake",
                                                        family = "hostfake",
                                                        outcome = "success",
                                                        rationale = "Recovered HTTPS",
                                                        succeededTargets = 3,
                                                        totalTargets = 3,
                                                        weightedSuccessScore = 5,
                                                        totalWeight = 5,
                                                        qualityScore = 9,
                                                        averageLatencyMs = 180,
                                                        proxyConfigJson = hostfakeConfigJson,
                                                        notes = listOf("Adaptive warm-up applied"),
                                                    ),
                                                ),
                                            quicCandidates =
                                                listOf(
                                                    StrategyProbeCandidateSummary(
                                                        id = "quic_disabled",
                                                        label = "QUIC disabled",
                                                        family = "quic_disabled",
                                                        outcome = "not_applicable",
                                                        rationale = "No QUIC target responded",
                                                        succeededTargets = 0,
                                                        totalTargets = 0,
                                                        weightedSuccessScore = 0,
                                                        totalWeight = 0,
                                                        qualityScore = 0,
                                                    ),
                                                    StrategyProbeCandidateSummary(
                                                        id = "quic_realistic_burst",
                                                        label = "QUIC realistic burst",
                                                        family = "quic_burst",
                                                        outcome = "success",
                                                        rationale = "Recovered QUIC",
                                                        succeededTargets = 1,
                                                        totalTargets = 1,
                                                        weightedSuccessScore = 2,
                                                        totalWeight = 2,
                                                        qualityScore = 4,
                                                        averageLatencyMs = 220,
                                                    ),
                                                ),
                                            recommendation =
                                                StrategyProbeRecommendation(
                                                    tcpCandidateId = "tlsrec_hostfake",
                                                    tcpCandidateLabel = "TLS record + hostfake",
                                                    quicCandidateId = "quic_realistic_burst",
                                                    quicCandidateLabel = "QUIC realistic burst",
                                                    rationale = "Best combined recovery",
                                                    recommendedProxyConfigJson = hostfakeConfigJson,
                                                ),
                                            auditAssessment =
                                                StrategyProbeAuditAssessment(
                                                    dnsShortCircuited = false,
                                                    coverage =
                                                        StrategyProbeAuditCoverage(
                                                            tcpCandidatesPlanned = 11,
                                                            tcpCandidatesExecuted = 3,
                                                            tcpCandidatesSkipped = 0,
                                                            tcpCandidatesNotApplicable = 0,
                                                            quicCandidatesPlanned = 2,
                                                            quicCandidatesExecuted = 2,
                                                            quicCandidatesSkipped = 0,
                                                            quicCandidatesNotApplicable = 1,
                                                            tcpWinnerSucceededTargets = 3,
                                                            tcpWinnerTotalTargets = 3,
                                                            quicWinnerSucceededTargets = 1,
                                                            quicWinnerTotalTargets = 1,
                                                            matrixCoveragePercent = 38,
                                                            winnerCoveragePercent = 100,
                                                        ),
                                                    confidence =
                                                        StrategyProbeAuditConfidence(
                                                            level = StrategyProbeAuditConfidenceLevel.MEDIUM,
                                                            score = 75,
                                                            rationale =
                                                                "The audit did not execute enough of the planned matrix to fully trust the winner",
                                                            warnings =
                                                                listOf(
                                                                    "TCP matrix coverage stayed below 75% of planned candidates.",
                                                                ),
                                                        ),
                                                ),
                                        ),
                                    ),
                            ),
                        )
                }

            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val report = requireNotNull(viewModel.uiState.value.scan.strategyProbeReport)
            val metrics = report.summaryMetrics.associate { it.label to it.value }
            assertEquals("full_matrix_v1", report.suiteId)
            assertEquals("Automatic audit", report.suiteLabel)
            assertEquals("TCP / HTTP / HTTPS matrix", report.families.first().title)
            assertEquals("2", metrics.getValue("Worked"))
            assertEquals("1", metrics.getValue("Partial"))
            assertEquals("1", metrics.getValue("Failed"))
            assertEquals("1", metrics.getValue("N/A"))
            assertEquals(
                StrategyProbeAuditConfidenceLevel.MEDIUM,
                requireNotNull(report.auditAssessment).confidence.level,
            )
            assertEquals(38, requireNotNull(report.auditAssessment).coverage.matrixCoveragePercent)

            viewModel.selectStrategyProbeCandidate(report.candidateDetails.getValue("tlsrec_hostfake"))
            advanceUntilIdle()

            val selected = requireNotNull(viewModel.uiState.value.selectedStrategyProbeCandidate)
            assertEquals("Hostfake", selected.familyLabel)
            assertEquals("Automatic audit", selected.suiteLabel)
            assertTrue(selected.notes.contains("Adaptive warm-up applied"))
            assertTrue(selected.signature.any { it.label == "Chain" && it.value.contains("hostfake") })
            assertEquals("HTTPS results", selected.resultGroups.first().title)
            assertEquals(
                "audit.example",
                selected.resultGroups
                    .first()
                    .items
                    .first()
                    .target,
            )

            viewModel.dismissStrategyProbeCandidate()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.selectedStrategyProbeCandidate)
            collector.cancel()
        }

    @Test
    fun `automatic audit completion auto opens finished session detail`() =
        runTest {
            val profileId = "automatic-audit"
            val detail =
                DiagnosticSessionDetail(
                    session =
                        session(
                            id = "session-RAW_PATH",
                            profileId = profileId,
                            pathMode = "RAW_PATH",
                            summary = "Audit done",
                        ),
                    results =
                        listOf(
                            ProbeResultEntity(
                                id = "probe-audit",
                                sessionId = "session-RAW_PATH",
                                probeType = "https",
                                target = "audit.example",
                                outcome = "ok",
                                detailJson = json.encodeToString(listOf(ProbeDetail("candidateId", "tlsrec_hostfake"))),
                                createdAt = 3L,
                            ),
                        ),
                    snapshots = listOf(snapshot(id = "snapshot-audit", sessionId = "session-RAW_PATH")),
                    context = context(id = "context-audit", sessionId = "session-RAW_PATH"),
                    events = emptyList(),
                )
            val manager =
                FakeDiagnosticsManager(detail = detail).apply {
                    profilesState.value =
                        listOf(
                            DiagnosticProfileEntity(
                                id = profileId,
                                name = "Automatic audit",
                                source = "bundled",
                                version = 1,
                                requestJson =
                                    strategyProbeProfileRequest(
                                        json = json,
                                        profileId = profileId,
                                        displayName = "Automatic audit",
                                        suiteId = "full_matrix_v1",
                                    ),
                                updatedAt = 1L,
                            ),
                        )
                }

            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.startRawScan()
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.selectedSessionDetail)

            manager.sessionsState.value = listOf(detail.session)
            advanceUntilIdle()

            val selected = requireNotNull(viewModel.uiState.value.selectedSessionDetail)
            assertEquals("session-RAW_PATH", selected.session.id)
            assertEquals("session-RAW_PATH", viewModel.uiState.value.sessions.focusedSessionId)
            collector.cancel()
        }

    @Test
    fun `hidden automatic probe conflict opens takeover dialog`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    scanController.onStartScan = {
                        DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution(
                            requestId = "hidden-request",
                            profileName = "Automatic probing",
                            pathMode = ScanPathMode.RAW_PATH,
                            scanKind = ScanKind.STRATEGY_PROBE,
                            isFullAudit = false,
                        )
                    }
                }
            val viewModel =
                createDiagnosticsViewModel(
                    RuntimeEnvironment.getApplication(),
                    manager,
                    FakeAppSettingsRepository(),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.startRawScan()
            advanceUntilIdle()

            val dialog = requireNotNull(viewModel.uiState.value.scan.hiddenProbeConflictDialog)
            assertEquals("hidden-request", dialog.requestId)
            assertEquals("Automatic probing", dialog.profileName)
            assertEquals(ScanPathMode.RAW_PATH, dialog.pathMode)
            assertEquals(ScanKind.STRATEGY_PROBE, dialog.scanKind)
            assertFalse(viewModel.uiState.value.scan.isBusy)
            assertNull(viewModel.uiState.value.selectedSessionDetail)
            collector.cancel()
        }

    @Test
    fun `wait queues manual scan and auto starts after hidden probe finishes`() =
        runTest {
            var resolveCalls = 0
            val manager =
                FakeDiagnosticsManager().apply {
                    scanController.hiddenAutomaticProbeActive.value = true
                    scanController.onStartScan = {
                        DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution(
                            requestId = "hidden-request",
                            profileName = "Automatic probing",
                            pathMode = ScanPathMode.RAW_PATH,
                            scanKind = ScanKind.STRATEGY_PROBE,
                            isFullAudit = false,
                        )
                    }
                    scanController.onResolveHiddenProbeConflict = { requestId, action ->
                        resolveCalls += 1
                        assertEquals("hidden-request", requestId)
                        assertEquals(HiddenProbeConflictAction.WAIT, action)
                        DiagnosticsManualScanResolution.Started("queued-session")
                    }
                }
            val viewModel =
                createDiagnosticsViewModel(
                    RuntimeEnvironment.getApplication(),
                    manager,
                    FakeAppSettingsRepository(),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.startRawScan()
            advanceUntilIdle()
            viewModel.waitForHiddenProbeAndRun()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.scan.hiddenProbeConflictDialog)
            assertNotNull(viewModel.uiState.value.scan.queuedManualScanRequest)
            assertEquals(0, resolveCalls)

            manager.scanController.hiddenAutomaticProbeActive.value = false
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.scan.queuedManualScanRequest)
            assertEquals(1, resolveCalls)
            collector.cancel()
        }

    @Test
    fun `cancel and run resolves hidden probe conflict immediately`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    scanController.hiddenAutomaticProbeActive.value = true
                    scanController.onStartScan = {
                        DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution(
                            requestId = "hidden-request",
                            profileName = "Automatic probing",
                            pathMode = ScanPathMode.RAW_PATH,
                            scanKind = ScanKind.STRATEGY_PROBE,
                            isFullAudit = false,
                        )
                    }
                    scanController.onResolveHiddenProbeConflict = { requestId, action ->
                        assertEquals("hidden-request", requestId)
                        assertEquals(HiddenProbeConflictAction.CANCEL_AND_RUN, action)
                        DiagnosticsManualScanResolution.Started("manual-session")
                    }
                }
            val viewModel =
                createDiagnosticsViewModel(
                    RuntimeEnvironment.getApplication(),
                    manager,
                    FakeAppSettingsRepository(),
                )
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            val effectDeferred = async { viewModel.effects.first() }
            advanceUntilIdle()

            viewModel.startRawScan()
            advanceUntilIdle()
            viewModel.cancelHiddenProbeAndRun()
            advanceUntilIdle()

            val effect = effectDeferred.await() as DiagnosticsEffect.ScanStarted
            assertEquals("Automatic probing", effect.scanTypeLabel)
            assertNull(viewModel.uiState.value.scan.hiddenProbeConflictDialog)
            assertNull(viewModel.uiState.value.scan.queuedManualScanRequest)
            collector.cancel()
        }

    @Test
    fun `generic scan start failure emits fallback error and keeps scan idle`() =
        runTest {
            val appContext = RuntimeEnvironment.getApplication()
            val manager =
                FakeDiagnosticsManager().apply {
                    scanController.onStartScan = {
                        throw IllegalStateException("boom")
                    }
                }
            val viewModel = createDiagnosticsViewModel(appContext, manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val effectDeferred = async { viewModel.effects.first() }
            viewModel.startInPathScan()
            advanceUntilIdle()

            val effect = effectDeferred.await() as DiagnosticsEffect.ScanStartFailed
            assertEquals(appContext.getString(com.poyka.ripdpi.R.string.diagnostics_error_start_failed), effect.message)
            assertFalse(viewModel.uiState.value.scan.isBusy)
            assertNull(viewModel.uiState.value.selectedSessionDetail)
            collector.cancel()
        }

    @Test
    fun `automatic audit completion auto opens finished session detail after fast completion race`() =
        runTest {
            val profileId = "automatic-audit"
            val sessionId = "session-RAW_PATH"
            val detail =
                DiagnosticSessionDetail(
                    session =
                        session(
                            id = sessionId,
                            profileId = profileId,
                            pathMode = "RAW_PATH",
                            summary = "Audit done",
                        ),
                    results =
                        listOf(
                            ProbeResultEntity(
                                id = "probe-audit-fast",
                                sessionId = sessionId,
                                probeType = "https",
                                target = "audit.example",
                                outcome = "ok",
                                detailJson = json.encodeToString(listOf(ProbeDetail("candidateId", "tlsrec_hostfake"))),
                                createdAt = 3L,
                            ),
                        ),
                    snapshots = listOf(snapshot(id = "snapshot-audit-fast", sessionId = sessionId)),
                    context = context(id = "context-audit-fast", sessionId = sessionId),
                    events = emptyList(),
                )
            val manager =
                FakeDiagnosticsManager(detail = detail).apply {
                    profilesState.value =
                        listOf(
                            DiagnosticProfileEntity(
                                id = profileId,
                                name = "Automatic audit",
                                source = "bundled",
                                version = 1,
                                requestJson =
                                    strategyProbeProfileRequest(
                                        json = json,
                                        profileId = profileId,
                                        displayName = "Automatic audit",
                                        suiteId = "full_matrix_v1",
                                    ),
                                updatedAt = 1L,
                            ),
                        )
                    scanController.onStartScan = {
                        progressState.value =
                            ScanProgress(
                                sessionId = sessionId,
                                phase = "tcp",
                                completedSteps = 1,
                                totalSteps = 2,
                                message = "Testing TCP candidate",
                            )
                        sessionsState.value = listOf(detail.session)
                        progressState.value = null
                        DiagnosticsManualScanStartResult.Started(sessionId)
                    }
                }

            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.startRawScan()
            advanceUntilIdle()

            val selected = requireNotNull(viewModel.uiState.value.selectedSessionDetail)
            assertEquals(sessionId, selected.session.id)
            assertEquals(sessionId, viewModel.uiState.value.sessions.focusedSessionId)
            collector.cancel()
        }

    @Test
    fun `select session loads grouped detail model`() =
        runTest {
            val detail =
                DiagnosticSessionDetail(
                    session =
                        session(
                            id = "session-1",
                            profileId = "default",
                            pathMode = "RAW_PATH",
                            summary = "Session",
                        ),
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
                    context = context(id = "context-1", sessionId = "session-1"),
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
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.selectSession("session-1")
            advanceUntilIdle()

            val selected = viewModel.uiState.value.selectedSessionDetail
            assertNotNull(selected)
            assertEquals("Session", selected?.session?.title)
            assertEquals(
                1,
                selected
                    ?.probeGroups
                    ?.first()
                    ?.items
                    ?.size,
            )
            assertEquals(
                "example.org",
                selected
                    ?.probeGroups
                    ?.first()
                    ?.items
                    ?.first()
                    ?.target,
            )
            assertEquals("Service", selected?.contextGroups?.first()?.title)
            collector.cancel()
        }

    @Test
    fun `approaches section switches modes and loads detail`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    sessionsState.value =
                        listOf(
                            session(
                                id = "session-1",
                                profileId = "default",
                                pathMode = "RAW_PATH",
                                summary = "Validated success",
                            ),
                        )
                    approachStatsState.value =
                        listOf(
                            sampleApproachSummary(
                                kind = BypassApproachKind.Profile,
                                id = "default",
                            ).copy(displayName = "Default", secondaryLabel = "Profile"),
                            sampleApproachSummary(
                                kind = BypassApproachKind.Strategy,
                                id = "strategy-1",
                            ),
                        )
                }
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.selectSection(DiagnosticsSection.Approaches)
            viewModel.selectApproachMode(DiagnosticsApproachMode.Strategies)
            advanceUntilIdle()

            val beforeDetail = viewModel.uiState.value
            assertEquals(DiagnosticsSection.Approaches, beforeDetail.selectedSection)
            assertEquals(1, beforeDetail.approaches.rows.size)
            assertEquals(
                "VPN Split",
                beforeDetail.approaches.rows
                    .first()
                    .title,
            )

            viewModel.selectApproach("strategy-1")
            advanceUntilIdle()

            val detail = viewModel.uiState.value.selectedApproachDetail
            assertNotNull(detail)
            assertEquals("VPN Split", detail?.approach?.title)
            assertTrue(detail?.signature?.any { it.label == "Mode" } == true)
            collector.cancel()
        }

    @Test
    fun `approaches detail humanizes fake tls signature`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    approachStatsState.value =
                        listOf(
                            sampleApproachSummary(
                                kind = BypassApproachKind.Strategy,
                                id = "strategy-fake-tls",
                            ),
                        )
                    strategySignatureOverride =
                        BypassStrategySignature(
                            mode = "VPN",
                            configSource = "ui",
                            hostAutolearn = "enabled",
                            desyncMethod = "fake",
                            chainSummary = "tcp: fake(host)",
                            protocolToggles = listOf("HTTPS"),
                            tlsRecordSplitEnabled = false,
                            tlsRecordMarker = null,
                            splitMarker = "host",
                            fakeSniMode = "fixed",
                            fakeSniValue = "alt.example.org",
                            fakeTlsBaseMode = "original",
                            fakeTlsMods = listOf("rand", "dupsid", "padencap"),
                            fakeTlsSize = -24,
                            fakeOffsetMarker = "host+1",
                            routeGroup = "2",
                        )
                }
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.selectSection(DiagnosticsSection.Approaches)
            viewModel.selectApproachMode(DiagnosticsApproachMode.Strategies)
            advanceUntilIdle()

            viewModel.selectApproach("strategy-fake-tls")
            advanceUntilIdle()

            val signature =
                viewModel.uiState.value.selectedApproachDetail
                    ?.signature
                    .orEmpty()
            assertTrue(signature.any { it.label == "Fake TLS base" && it.value == "Original ClientHello" })
            assertTrue(signature.any { it.label == "Fake TLS SNI" && it.value == "Fixed (alt.example.org)" })
            assertTrue(
                signature.any {
                    it.label == "Fake TLS mods" &&
                        it.value == "Randomize TLS material, Copy Session ID, Padding camouflage"
                },
            )
            assertTrue(signature.any { it.label == "Fake TLS size" && it.value == "Input minus 24 bytes" })
            collector.cancel()
        }

    @Test
    fun `approaches detail keeps hostfake chain visible without fake tls rows`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    approachStatsState.value =
                        listOf(
                            sampleApproachSummary(
                                kind = BypassApproachKind.Strategy,
                                id = "strategy-hostfake",
                            ),
                        )
                    strategySignatureOverride =
                        BypassStrategySignature(
                            mode = "VPN",
                            configSource = "ui",
                            hostAutolearn = "enabled",
                            desyncMethod = "fake",
                            chainSummary =
                                "tcp: tlsrec(extlen) -> hostfake(endhost+8 midhost=midsld host=googlevideo.com) -> split(midsld)",
                            protocolToggles = listOf("HTTP", "HTTPS"),
                            tlsRecordSplitEnabled = true,
                            tlsRecordMarker = "extlen",
                            splitMarker = "endhost+8",
                            fakeSniMode = null,
                            fakeSniValue = null,
                            fakeTlsBaseMode = null,
                            fakeTlsMods = emptyList(),
                            fakeTlsSize = null,
                            fakeOffsetMarker = null,
                            routeGroup = "4",
                        )
                }
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.selectSection(DiagnosticsSection.Approaches)
            viewModel.selectApproachMode(DiagnosticsApproachMode.Strategies)
            advanceUntilIdle()

            viewModel.selectApproach("strategy-hostfake")
            advanceUntilIdle()

            val signature =
                viewModel.uiState.value.selectedApproachDetail
                    ?.signature
                    .orEmpty()
            assertTrue(
                signature.any {
                    it.label == "Chain" &&
                        it.value.contains("hostfake(endhost+8 midhost=midsld host=googlevideo.com)")
                },
            )
            assertTrue(signature.any { it.label == "TLS record marker" && it.value == "extlen" })
            assertTrue(signature.any { it.label == "Split marker" && it.value == "endhost+8" })
            assertFalse(signature.any { it.label.startsWith("Fake TLS") })
            collector.cancel()
        }

    @Test
    fun `approaches detail preserves fake approximation chain labels`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    approachStatsState.value =
                        listOf(
                            sampleApproachSummary(
                                kind = BypassApproachKind.Strategy,
                                id = "strategy-fakedsplit",
                            ),
                        )
                    strategySignatureOverride =
                        BypassStrategySignature(
                            mode = "VPN",
                            configSource = "ui",
                            hostAutolearn = "enabled",
                            desyncMethod = "fake",
                            chainSummary = "tcp: tlsrec(extlen) -> fakedsplit(host+1)",
                            protocolToggles = listOf("HTTPS"),
                            tlsRecordSplitEnabled = true,
                            tlsRecordMarker = "extlen",
                            splitMarker = "host+1",
                            fakeTlsBaseMode = "original",
                            fakeTlsMods = listOf("dupsid"),
                            routeGroup = "22",
                        )
                }
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.selectSection(DiagnosticsSection.Approaches)
            viewModel.selectApproachMode(DiagnosticsApproachMode.Strategies)
            advanceUntilIdle()

            viewModel.selectApproach("strategy-fakedsplit")
            advanceUntilIdle()

            val signature =
                viewModel.uiState.value.selectedApproachDetail
                    ?.signature
                    .orEmpty()
            assertTrue(signature.any { it.label == "Chain" && it.value.contains("fakedsplit(host+1)") })
            assertTrue(signature.any { it.label == "Fake TLS base" && it.value == "Original ClientHello" })
            collector.cancel()
        }

    @Test
    fun `approaches detail renders activation window fields`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    approachStatsState.value =
                        listOf(
                            sampleApproachSummary(
                                kind = BypassApproachKind.Strategy,
                                id = "strategy-activation-window",
                            ),
                        )
                    strategySignatureOverride =
                        BypassStrategySignature(
                            mode = "VPN",
                            configSource = "ui",
                            hostAutolearn = "disabled",
                            desyncMethod = "fake",
                            chainSummary = "tcp: fake(host when_round=1-2 when_size=64-512)",
                            protocolToggles = listOf("HTTP", "HTTPS"),
                            tlsRecordSplitEnabled = false,
                            activationRound = "2-4",
                            activationPayloadSize = "64-512",
                            activationStreamBytes = "0-2047",
                            routeGroup = "12",
                        )
                }
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.selectSection(DiagnosticsSection.Approaches)
            viewModel.selectApproachMode(DiagnosticsApproachMode.Strategies)
            advanceUntilIdle()

            viewModel.selectApproach("strategy-activation-window")
            advanceUntilIdle()

            val signature =
                viewModel.uiState.value.selectedApproachDetail
                    ?.signature
                    .orEmpty()
            assertTrue(signature.any { it.label == "Activation round" && it.value == "2-4" })
            assertTrue(signature.any { it.label == "Activation payload size" && it.value == "64-512" })
            assertTrue(signature.any { it.label == "Activation stream bytes" && it.value == "0-2047" })
            collector.cancel()
        }

    @Test
    fun `approaches detail humanizes adaptive markers`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    approachStatsState.value =
                        listOf(
                            sampleApproachSummary(
                                kind = BypassApproachKind.Strategy,
                                id = "strategy-adaptive",
                            ),
                        )
                    strategySignatureOverride =
                        BypassStrategySignature(
                            mode = "VPN",
                            configSource = "ui",
                            hostAutolearn = "disabled",
                            desyncMethod = "split",
                            chainSummary = "tcp: split(adaptive balanced)",
                            protocolToggles = listOf("HTTP", "HTTPS"),
                            tlsRecordSplitEnabled = true,
                            tlsRecordMarker = "auto(sniext)",
                            splitMarker = "auto(balanced)",
                            routeGroup = "13",
                        )
                }
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.selectSection(DiagnosticsSection.Approaches)
            viewModel.selectApproachMode(DiagnosticsApproachMode.Strategies)
            advanceUntilIdle()

            viewModel.selectApproach("strategy-adaptive")
            advanceUntilIdle()

            val signature =
                viewModel.uiState.value.selectedApproachDetail
                    ?.signature
                    .orEmpty()
            assertTrue(signature.any { it.label == "Chain" && it.value.contains("adaptive balanced") })
            assertTrue(signature.any { it.label == "TLS record marker" && it.value == "adaptive TLS SNI extension" })
            assertTrue(signature.any { it.label == "Split marker" && it.value == "adaptive balanced" })
            collector.cancel()
        }

    @Test
    fun `approaches detail humanizes quic fake profile`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    approachStatsState.value =
                        listOf(
                            sampleApproachSummary(
                                kind = BypassApproachKind.Strategy,
                                id = "strategy-quic-fake",
                            ),
                        )
                    strategySignatureOverride =
                        BypassStrategySignature(
                            mode = "VPN",
                            configSource = "ui",
                            hostAutolearn = "disabled",
                            desyncMethod = "disorder",
                            chainSummary = "udp: fake_burst(3)",
                            protocolToggles = listOf("UDP"),
                            tlsRecordSplitEnabled = false,
                            quicFakeProfile = "realistic_initial",
                            quicFakeHost = "video.example.test",
                            routeGroup = "6",
                        )
                }
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.selectSection(DiagnosticsSection.Approaches)
            viewModel.selectApproachMode(DiagnosticsApproachMode.Strategies)
            advanceUntilIdle()

            viewModel.selectApproach("strategy-quic-fake")
            advanceUntilIdle()

            val signature =
                viewModel.uiState.value.selectedApproachDetail
                    ?.signature
                    .orEmpty()
            assertTrue(signature.any { it.label == "QUIC fake profile" && it.value == "Realistic Initial" })
            assertTrue(signature.any { it.label == "QUIC fake host" && it.value == "video.example.test" })
            collector.cancel()
        }

    @Test
    fun `approaches detail humanizes compatibility quic fake profile without host`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    approachStatsState.value =
                        listOf(
                            sampleApproachSummary(
                                kind = BypassApproachKind.Strategy,
                                id = "strategy-quic-compat",
                            ),
                        )
                    strategySignatureOverride =
                        BypassStrategySignature(
                            mode = "VPN",
                            configSource = "ui",
                            hostAutolearn = "disabled",
                            desyncMethod = "disorder",
                            chainSummary = "udp: fake_burst(2)",
                            protocolToggles = listOf("UDP"),
                            tlsRecordSplitEnabled = false,
                            quicFakeProfile = "compat_default",
                            routeGroup = "2",
                        )
                }
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.selectSection(DiagnosticsSection.Approaches)
            viewModel.selectApproachMode(DiagnosticsApproachMode.Strategies)
            advanceUntilIdle()

            viewModel.selectApproach("strategy-quic-compat")
            advanceUntilIdle()

            val signature =
                viewModel.uiState.value.selectedApproachDetail
                    ?.signature
                    .orEmpty()
            assertTrue(signature.any { it.label == "QUIC fake profile" && it.value == "Compatibility blob" })
            assertFalse(signature.any { it.label == "QUIC fake host" })
            collector.cancel()
        }

    @Test
    fun `approaches detail humanizes fake payload library profiles`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    approachStatsState.value =
                        listOf(
                            sampleApproachSummary(
                                kind = BypassApproachKind.Strategy,
                                id = "strategy-fake-payload-library",
                            ),
                        )
                    strategySignatureOverride =
                        BypassStrategySignature(
                            mode = "VPN",
                            configSource = "ui",
                            hostAutolearn = "disabled",
                            desyncMethod = "fake",
                            chainSummary = "tcp: fake(host) | udp: fake_burst(3)",
                            protocolToggles = listOf("HTTP", "HTTPS", "UDP"),
                            tlsRecordSplitEnabled = false,
                            httpFakeProfile = HttpFakeProfileCloudflareGet,
                            tlsFakeProfile = TlsFakeProfileGoogleChrome,
                            udpFakeProfile = UdpFakeProfileDnsQuery,
                            routeGroup = "8",
                        )
                }
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.selectSection(DiagnosticsSection.Approaches)
            viewModel.selectApproachMode(DiagnosticsApproachMode.Strategies)
            advanceUntilIdle()

            viewModel.selectApproach("strategy-fake-payload-library")
            advanceUntilIdle()

            val signature =
                viewModel.uiState.value.selectedApproachDetail
                    ?.signature
                    .orEmpty()
            assertTrue(signature.any { it.label == "HTTP fake profile" && it.value == "Cloudflare GET" })
            assertTrue(signature.any { it.label == "TLS fake profile" && it.value == "Google Chrome" })
            assertTrue(signature.any { it.label == "UDP fake profile" && it.value == "DNS query" })
            assertFalse(signature.any { it.label == "Fake payload source" })
            collector.cancel()
        }

    @Test
    fun `approaches detail humanizes grouped http parser evasions`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    approachStatsState.value =
                        listOf(
                            sampleApproachSummary(
                                kind = BypassApproachKind.Strategy,
                                id = "strategy-http-parser-evasions",
                            ),
                        )
                    strategySignatureOverride =
                        BypassStrategySignature(
                            mode = "VPN",
                            configSource = "ui",
                            hostAutolearn = "disabled",
                            desyncMethod = "disorder",
                            chainSummary = "tcp: disorder(1)",
                            protocolToggles = listOf("HTTP", "HTTPS"),
                            tlsRecordSplitEnabled = false,
                            httpParserEvasions =
                                listOf(
                                    "host_mixed_case",
                                    "domain_mixed_case",
                                    "host_remove_spaces",
                                    "unix_eol",
                                    "method_eol",
                                ),
                            routeGroup = "14",
                        )
                }
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.selectSection(DiagnosticsSection.Approaches)
            viewModel.selectApproachMode(DiagnosticsApproachMode.Strategies)
            advanceUntilIdle()

            viewModel.selectApproach("strategy-http-parser-evasions")
            advanceUntilIdle()

            val signature =
                viewModel.uiState.value.selectedApproachDetail
                    ?.signature
                    .orEmpty()
            assertTrue(
                signature.any {
                    it.label == "HTTP parser evasions" &&
                        it.value ==
                        "Host mixed case, Domain mixed case, Host remove spaces, Unix line endings, Method EOL shift"
                },
            )
            collector.cancel()
        }

    @Test
    fun `approaches detail omits http parser evasions when command line mode is active`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    approachStatsState.value =
                        listOf(
                            sampleApproachSummary(
                                kind = BypassApproachKind.Strategy,
                                id = "strategy-http-parser-evasions-cli",
                            ),
                        )
                    strategySignatureOverride =
                        BypassStrategySignature(
                            mode = "VPN",
                            configSource = "command line",
                            hostAutolearn = "command line",
                            desyncMethod = "disorder",
                            chainSummary = "tcp: disorder(1)",
                            protocolToggles = listOf("HTTP", "HTTPS"),
                            tlsRecordSplitEnabled = false,
                            httpParserEvasions = emptyList(),
                            routeGroup = "15",
                        )
                }
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.selectSection(DiagnosticsSection.Approaches)
            viewModel.selectApproachMode(DiagnosticsApproachMode.Strategies)
            advanceUntilIdle()

            viewModel.selectApproach("strategy-http-parser-evasions-cli")
            advanceUntilIdle()

            val signature =
                viewModel.uiState.value.selectedApproachDetail
                    ?.signature
                    .orEmpty()
            assertFalse(signature.any { it.label == "HTTP parser evasions" })
            collector.cancel()
        }

    @Test
    fun `approaches detail humanizes aggressive only http parser evasions`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    approachStatsState.value =
                        listOf(
                            sampleApproachSummary(
                                kind = BypassApproachKind.Strategy,
                                id = "strategy-http-parser-aggressive",
                            ),
                        )
                    strategySignatureOverride =
                        BypassStrategySignature(
                            mode = "VPN",
                            configSource = "ui",
                            hostAutolearn = "disabled",
                            desyncMethod = "disorder",
                            chainSummary = "tcp: disorder(1)",
                            protocolToggles = listOf("HTTP", "HTTPS"),
                            tlsRecordSplitEnabled = false,
                            httpParserEvasions = listOf("unix_eol", "method_eol"),
                            routeGroup = "16",
                        )
                }
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.selectSection(DiagnosticsSection.Approaches)
            viewModel.selectApproachMode(DiagnosticsApproachMode.Strategies)
            advanceUntilIdle()

            viewModel.selectApproach("strategy-http-parser-aggressive")
            advanceUntilIdle()

            val signature =
                viewModel.uiState.value.selectedApproachDetail
                    ?.signature
                    .orEmpty()
            assertTrue(
                signature.any {
                    it.label == "HTTP parser evasions" &&
                        it.value == "Unix line endings, Method EOL shift"
                },
            )
            collector.cancel()
        }

    @Test
    fun `approaches detail humanizes adaptive fake ttl profile`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    approachStatsState.value =
                        listOf(
                            sampleApproachSummary(
                                kind = BypassApproachKind.Strategy,
                                id = "strategy-adaptive-fake-ttl",
                            ),
                        )
                    strategySignatureOverride =
                        BypassStrategySignature(
                            mode = "VPN",
                            configSource = "ui",
                            hostAutolearn = "disabled",
                            desyncMethod = "fake",
                            chainSummary = "tcp: fake(host)",
                            protocolToggles = listOf("HTTP", "HTTPS"),
                            tlsRecordSplitEnabled = false,
                            fakeTtlMode = "adaptive_custom",
                            adaptiveFakeTtlWindow = "4-16",
                            adaptiveFakeTtlFallback = 11,
                            adaptiveFakeTtlBias = 2,
                            routeGroup = "20",
                        )
                }
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.selectSection(DiagnosticsSection.Approaches)
            viewModel.selectApproachMode(DiagnosticsApproachMode.Strategies)
            advanceUntilIdle()

            viewModel.selectApproach("strategy-adaptive-fake-ttl")
            advanceUntilIdle()

            val signature =
                viewModel.uiState.value.selectedApproachDetail
                    ?.signature
                    .orEmpty()
            assertTrue(signature.any { it.label == "Fake TTL mode" && it.value == "Custom adaptive TTL" })
            assertTrue(signature.any { it.label == "Adaptive fake TTL window" && it.value == "4-16" })
            assertTrue(signature.any { it.label == "Adaptive fake TTL fallback" && it.value == "11" })
            assertTrue(
                signature.any {
                    it.label == "Adaptive fake TTL bias" &&
                        it.value == "Prefer higher TTLs first (+2)"
                },
            )
            collector.cancel()
        }

    @Test
    fun `approaches detail omits adaptive fake ttl fields in command line mode`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    approachStatsState.value =
                        listOf(
                            sampleApproachSummary(
                                kind = BypassApproachKind.Strategy,
                                id = "strategy-adaptive-fake-ttl-cli",
                            ),
                        )
                    strategySignatureOverride =
                        BypassStrategySignature(
                            mode = "VPN",
                            configSource = "command line",
                            hostAutolearn = "command line",
                            desyncMethod = "fake",
                            chainSummary = "tcp: fake(host)",
                            protocolToggles = listOf("HTTP", "HTTPS"),
                            tlsRecordSplitEnabled = false,
                            routeGroup = "21",
                        )
                }
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.selectSection(DiagnosticsSection.Approaches)
            viewModel.selectApproachMode(DiagnosticsApproachMode.Strategies)
            advanceUntilIdle()

            viewModel.selectApproach("strategy-adaptive-fake-ttl-cli")
            advanceUntilIdle()

            val signature =
                viewModel.uiState.value.selectedApproachDetail
                    ?.signature
                    .orEmpty()
            assertFalse(signature.any { it.label == "Fake TTL mode" })
            assertFalse(signature.any { it.label == "Adaptive fake TTL window" })
            assertFalse(signature.any { it.label == "Adaptive fake TTL fallback" })
            assertFalse(signature.any { it.label == "Adaptive fake TTL bias" })
            collector.cancel()
        }

    @Test
    fun `approaches detail prefers custom raw fake payload source over library labels`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
                    approachStatsState.value =
                        listOf(
                            sampleApproachSummary(
                                kind = BypassApproachKind.Strategy,
                                id = "strategy-custom-raw-fake",
                            ),
                        )
                    strategySignatureOverride =
                        BypassStrategySignature(
                            mode = "VPN",
                            configSource = "command line",
                            hostAutolearn = "command line",
                            desyncMethod = "fake",
                            chainSummary = "tcp: fake(host)",
                            protocolToggles = listOf("HTTP", "HTTPS"),
                            tlsRecordSplitEnabled = false,
                            fakePayloadSource = "custom_raw",
                            routeGroup = "11",
                        )
                }
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.selectSection(DiagnosticsSection.Approaches)
            viewModel.selectApproachMode(DiagnosticsApproachMode.Strategies)
            advanceUntilIdle()

            viewModel.selectApproach("strategy-custom-raw-fake")
            advanceUntilIdle()

            val signature =
                viewModel.uiState.value.selectedApproachDetail
                    ?.signature
                    .orEmpty()
            assertTrue(signature.any { it.label == "Fake payload source" && it.value == "Custom raw fake payload" })
            assertFalse(signature.any { it.label == "HTTP fake profile" })
            assertFalse(signature.any { it.label == "TLS fake profile" })
            assertFalse(signature.any { it.label == "UDP fake profile" })
            collector.cancel()
        }

    @Test
    fun `snapshot detail shows wifi and cellular transport fields`() =
        runTest {
            val wifiDetail =
                DiagnosticSessionDetail(
                    session =
                        session(
                            id = "session-wifi",
                            profileId = "default",
                            pathMode = "RAW_PATH",
                            summary = "Wi-Fi",
                        ),
                    results = emptyList(),
                    snapshots =
                        listOf(
                            snapshot(
                                id = "snapshot-wifi",
                                sessionId = "session-wifi",
                                transport = "wifi",
                            ),
                        ),
                    context = context(id = "context-wifi", sessionId = "session-wifi"),
                    events = emptyList(),
                )
            val manager = FakeDiagnosticsManager(detail = wifiDetail)
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.selectSession("session-wifi")
            advanceUntilIdle()

            val wifiFields =
                viewModel.uiState.value.selectedSessionDetail
                    ?.snapshots
                    ?.first()
                    ?.fields
                    .orEmpty()
            assertTrue(wifiFields.any { it.label == "Wi-Fi band" && it.value == "5 GHz" })
            assertTrue(wifiFields.any { it.label == "Wi-Fi SSID" && it.value == "redacted" })

            manager.detail =
                wifiDetail.copy(
                    session =
                        session(
                            id = "session-cell",
                            profileId = "default",
                            pathMode = "RAW_PATH",
                            summary = "Cell",
                        ),
                    snapshots =
                        listOf(
                            snapshot(
                                id = "snapshot-cell",
                                sessionId = "session-cell",
                                transport = "cellular",
                                cellularDetails =
                                    CellularNetworkDetails(
                                        carrierName = "Example Carrier",
                                        simOperatorName = "Example Carrier",
                                        networkOperatorName = "Example Carrier LTE",
                                        networkCountryIso = "us",
                                        simCountryIso = "us",
                                        operatorCode = "310260",
                                        simOperatorCode = "310260",
                                        dataNetworkType = "NR",
                                        voiceNetworkType = "LTE",
                                        dataState = "connected",
                                        serviceState = "in_service",
                                        isNetworkRoaming = false,
                                        carrierId = 42,
                                        simCarrierId = 42,
                                        signalLevel = 4,
                                        signalDbm = -95,
                                    ),
                            ),
                        ),
                )
            viewModel.selectSession("session-cell")
            advanceUntilIdle()

            val cellFields =
                viewModel.uiState.value.selectedSessionDetail
                    ?.snapshots
                    ?.first()
                    ?.fields
                    .orEmpty()
            assertTrue(cellFields.any { it.label == "Carrier" && it.value == "Example Carrier" })
            assertTrue(cellFields.any { it.label == "Data network" && it.value == "NR" })
            collector.cancel()
        }

    @Test
    fun `share summary emits effect and archive actions use selected target session`() =
        runTest {
            val manager =
                FakeDiagnosticsManager().apply {
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
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
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
            val manager =
                FakeDiagnosticsManager().apply {
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
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            viewModel.toggleEventFilter(source = "Proxy")
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.events.events.size)
            assertEquals(
                "Proxy",
                viewModel.uiState.value.events.events
                    .first()
                    .source,
            )
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
            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
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
        reportJson: String? =
            json.encodeToString(
                EngineScanReportWire.serializer(),
                scanReport(
                    id = id,
                    profileId = profileId,
                    pathMode = ScanPathMode.valueOf(pathMode),
                    summary = summary,
                    probes =
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
    ): ScanSessionEntity =
        ScanSessionEntity(
            id = id,
            profileId = profileId,
            pathMode = pathMode,
            serviceMode = "VPN",
            status = "completed",
            summary = summary,
            reportJson = reportJson,
            startedAt = 1L,
            finishedAt = 2L,
        )

    private fun scanReport(
        id: String,
        profileId: String,
        summary: String,
        pathMode: ScanPathMode = ScanPathMode.RAW_PATH,
        probes: List<com.poyka.ripdpi.diagnostics.ProbeResult> = emptyList(),
    ): EngineScanReportWire =
        EngineScanReportWire(
            sessionId = id,
            profileId = profileId,
            pathMode = pathMode,
            startedAt = 1L,
            finishedAt = 2L,
            summary = summary,
            results =
                probes.map { result ->
                    EngineProbeResultWire(
                        probeType = result.probeType,
                        target = result.target,
                        outcome = result.outcome,
                        details = result.details,
                        probeRetryCount = result.probeRetryCount,
                    )
                },
        )

    private fun profileRequest(
        profileId: String,
        displayName: String,
        kind: ScanKind = ScanKind.CONNECTIVITY,
        family: DiagnosticProfileFamily = DiagnosticProfileFamily.GENERAL,
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
        codec: Json = json,
    ): String =
        codec.encodeToString(
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
                strategyProbe = strategyProbe,
            ),
        )

    private fun strategyProbeProfileRequest(
        json: Json,
        profileId: String = "automatic-probing",
        displayName: String = "Automatic probing",
        suiteId: String = "quick_v1",
        allowBackground: Boolean = suiteId == "quick_v1",
    ): String =
        profileRequest(
            profileId = profileId,
            displayName = displayName,
            kind = ScanKind.STRATEGY_PROBE,
            strategyProbe = StrategyProbeRequest(suiteId = suiteId),
            allowBackground = allowBackground,
            requiresRawPath = true,
            codec = json,
        )

    private fun strategyProbeScanReport(
        sessionId: String = "probe-session",
        profileId: String = "automatic-probing",
        suiteId: String = "quick_v1",
        summary: String = "Recommended hostfake",
        results: List<com.poyka.ripdpi.diagnostics.ProbeResult> = emptyList(),
        tcpCandidates: List<StrategyProbeCandidateSummary> =
            listOf(
                StrategyProbeCandidateSummary(
                    id = "tlsrec_hostfake",
                    label = "TLS record + hostfake",
                    family = "hostfake",
                    outcome = "success",
                    rationale = "Best HTTPS score",
                    succeededTargets = 6,
                    totalTargets = 6,
                    weightedSuccessScore = 9,
                    totalWeight = 9,
                    qualityScore = 24,
                    averageLatencyMs = 180,
                ),
            ),
        quicCandidates: List<StrategyProbeCandidateSummary> =
            listOf(
                StrategyProbeCandidateSummary(
                    id = "quic_realistic_burst",
                    label = "QUIC realistic burst",
                    family = "quic_burst",
                    outcome = "success",
                    rationale = "Best QUIC score",
                    succeededTargets = 2,
                    totalTargets = 2,
                    weightedSuccessScore = 4,
                    totalWeight = 4,
                    qualityScore = 8,
                    averageLatencyMs = 240,
                ),
            ),
        recommendation: StrategyProbeRecommendation =
            StrategyProbeRecommendation(
                tcpCandidateId = "tlsrec_hostfake",
                tcpCandidateLabel = "TLS record + hostfake",
                quicCandidateId = "quic_realistic_burst",
                quicCandidateLabel = "QUIC realistic burst",
                rationale = "Won by full HTTPS and QUIC success",
                recommendedProxyConfigJson = RipDpiProxyUIPreferences().toNativeConfigJson(),
                strategySignature =
                    BypassStrategySignature(
                        mode = "VPN",
                        configSource = "ui",
                        hostAutolearn = "disabled",
                        desyncMethod = "fake",
                        chainSummary = "tcp: tlsrec(extlen) -> hostfake(endhost+8)",
                        protocolToggles = listOf("HTTP", "HTTPS", "UDP"),
                        tlsRecordSplitEnabled = true,
                        tlsRecordMarker = "extlen",
                        splitMarker = "endhost+8",
                        quicFakeProfile = "realistic_initial",
                        routeGroup = null,
                    ),
            ),
        auditAssessment: StrategyProbeAuditAssessment? = null,
    ): EngineScanReportWire =
        EngineScanReportWire(
            sessionId = sessionId,
            profileId = profileId,
            pathMode = ScanPathMode.RAW_PATH,
            startedAt = 10L,
            finishedAt = 20L,
            summary = summary,
            results =
                results.map { result ->
                    EngineProbeResultWire(
                        probeType = result.probeType,
                        target = result.target,
                        outcome = result.outcome,
                        details = result.details,
                        probeRetryCount = result.probeRetryCount,
                    )
                },
            strategyProbeReport =
                StrategyProbeReport(
                    suiteId = suiteId,
                    tcpCandidates = tcpCandidates,
                    quicCandidates = quicCandidates,
                    recommendation = recommendation,
                    auditAssessment = auditAssessment,
                ),
        )

    private fun snapshot(
        id: String,
        sessionId: String?,
        connectionSessionId: String? = null,
        snapshotKind: String =
            when {
                connectionSessionId != null -> "connection_sample"
                sessionId == null -> "passive"
                else -> "post_scan"
            },
        transport: String = "wifi",
        cellularDetails: CellularNetworkDetails? = null,
    ): NetworkSnapshotEntity =
        NetworkSnapshotEntity(
            id = id,
            sessionId = sessionId,
            connectionSessionId = connectionSessionId,
            snapshotKind = snapshotKind,
            payloadJson =
                json.encodeToString(
                    NetworkSnapshotModel(
                        transport = transport,
                        capabilities = listOf("validated"),
                        dnsServers = listOf("1.1.1.1"),
                        privateDnsMode = "strict",
                        mtu = 1500,
                        localAddresses = listOf("192.168.1.4"),
                        publicIp = "198.51.100.8",
                        publicAsn = "AS64500",
                        captivePortalDetected = false,
                        networkValidated = true,
                        wifiDetails =
                            if (transport == "wifi") {
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
                                    gateway = "192.168.1.1",
                                    dhcpServer = "192.168.1.2",
                                    ipAddress = "192.168.1.4",
                                    subnetMask = "255.255.255.0",
                                    leaseDurationSeconds = 3600,
                                )
                            } else {
                                null
                            },
                        cellularDetails = if (transport == "cellular") cellularDetails else null,
                        capturedAt = 10L,
                    ),
                ),
            capturedAt = 10L,
        )

    private fun context(
        id: String,
        sessionId: String?,
        connectionSessionId: String? = null,
        contextKind: String =
            when {
                connectionSessionId != null -> "connection_sample"
                sessionId == null -> "passive"
                else -> "post_scan"
            },
        serviceStatus: String = "Running",
    ): DiagnosticContextEntity =
        DiagnosticContextEntity(
            id = id,
            sessionId = sessionId,
            connectionSessionId = connectionSessionId,
            contextKind = contextKind,
            payloadJson =
                json.encodeToString(
                    DiagnosticContextModel(
                        service =
                            ServiceContextModel(
                                serviceStatus = serviceStatus,
                                configuredMode = "VPN",
                                activeMode = "VPN",
                                selectedProfileId = "default",
                                selectedProfileName = "Default",
                                configSource = "ui",
                                proxyEndpoint = "127.0.0.1:1080",
                                desyncMethod = "split",
                                chainSummary = "tcp: split(1)",
                                routeGroup = "3",
                                sessionUptimeMs = 20_000L,
                                lastNativeErrorHeadline = "none",
                                restartCount = 2,
                                hostAutolearnEnabled = "enabled",
                                learnedHostCount = 3,
                                penalizedHostCount = 1,
                                lastAutolearnHost = "example.org",
                                lastAutolearnGroup = "2",
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
                    ),
                ),
            capturedAt = 12L,
        )

    private fun telemetry(
        id: String,
        sessionId: String? = null,
        connectionSessionId: String? = null,
        activeMode: String? = "VPN",
        connectionState: String = "Running",
        networkType: String = "wifi",
        publicIp: String? = "198.51.100.8",
        lastFailureClass: String? = null,
        lastFallbackAction: String? = null,
        txPackets: Long = 3,
        txBytes: Long = 4_000,
        rxPackets: Long = 5,
        rxBytes: Long = 6_000,
        createdAt: Long = 20L,
    ): TelemetrySampleEntity =
        TelemetrySampleEntity(
            id = id,
            sessionId = sessionId,
            connectionSessionId = connectionSessionId,
            activeMode = activeMode,
            connectionState = connectionState,
            networkType = networkType,
            publicIp = publicIp,
            lastFailureClass = lastFailureClass,
            lastFallbackAction = lastFallbackAction,
            txPackets = txPackets,
            txBytes = txBytes,
            rxPackets = rxPackets,
            rxBytes = rxBytes,
            createdAt = createdAt,
        )

    private fun connectionSession(
        id: String,
        connectionState: String = "Running",
        health: String = "active",
        serviceMode: String = "VPN",
        networkType: String = "wifi",
    ): DiagnosticConnectionSession =
        DiagnosticConnectionSession(
            id = id,
            startedAt = 10L,
            finishedAt = null,
            updatedAt = 20L,
            serviceMode = serviceMode,
            connectionState = connectionState,
            health = health,
            approachProfileId = null,
            approachProfileName = null,
            strategyId = "strategy-default",
            strategyLabel = "Strategy Default",
            networkType = networkType,
            txBytes = 1_000L,
            rxBytes = 2_000L,
            totalErrors = 0L,
            routeChanges = 0L,
            restartCount = 0,
            endedReason = null,
        )
}
