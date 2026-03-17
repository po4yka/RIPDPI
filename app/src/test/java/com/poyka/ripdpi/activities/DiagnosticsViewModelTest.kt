package com.poyka.ripdpi.activities

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.data.HttpFakeProfileCloudflareGet
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.TlsFakeProfileGoogleChrome
import com.poyka.ripdpi.data.UdpFakeProfileDnsQuery
import com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity
import com.poyka.ripdpi.data.diagnostics.DiagnosticProfileEntity
import com.poyka.ripdpi.data.diagnostics.ExportRecordEntity
import com.poyka.ripdpi.data.diagnostics.NativeSessionEventEntity
import com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity
import com.poyka.ripdpi.data.diagnostics.ProbeResultEntity
import com.poyka.ripdpi.data.diagnostics.ScanSessionEntity
import com.poyka.ripdpi.data.diagnostics.TelemetrySampleEntity
import com.poyka.ripdpi.diagnostics.BypassApproachDetail
import com.poyka.ripdpi.diagnostics.BypassApproachId
import com.poyka.ripdpi.diagnostics.BypassApproachKind
import com.poyka.ripdpi.diagnostics.BypassApproachSummary
import com.poyka.ripdpi.diagnostics.BypassOutcomeBreakdown
import com.poyka.ripdpi.diagnostics.BypassRuntimeHealthSummary
import com.poyka.ripdpi.diagnostics.BypassStrategySignature
import com.poyka.ripdpi.diagnostics.CellularNetworkDetails
import com.poyka.ripdpi.diagnostics.DeviceContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticContextModel
import com.poyka.ripdpi.diagnostics.DiagnosticSessionDetail
import com.poyka.ripdpi.diagnostics.DiagnosticsArchive
import com.poyka.ripdpi.diagnostics.DiagnosticsManager
import com.poyka.ripdpi.diagnostics.EnvironmentContextModel
import com.poyka.ripdpi.diagnostics.NetworkSnapshotModel
import com.poyka.ripdpi.diagnostics.PermissionContextModel
import com.poyka.ripdpi.diagnostics.ProbeDetail
import com.poyka.ripdpi.diagnostics.QuicTarget
import com.poyka.ripdpi.diagnostics.ResolverRecommendation
import com.poyka.ripdpi.diagnostics.ScanKind
import com.poyka.ripdpi.diagnostics.ScanPathMode
import com.poyka.ripdpi.diagnostics.ScanProgress
import com.poyka.ripdpi.diagnostics.ScanReport
import com.poyka.ripdpi.diagnostics.ScanRequest
import com.poyka.ripdpi.diagnostics.ServiceContextModel
import com.poyka.ripdpi.diagnostics.ShareSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.diagnostics.StrategyProbeRecommendation
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.diagnostics.StrategyProbeRequest
import com.poyka.ripdpi.diagnostics.SummaryMetric
import com.poyka.ripdpi.diagnostics.WifiNetworkDetails
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DiagnosticsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val json = Json

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
                                lastFailureClass = "dns_tampering",
                                lastFallbackAction = "resolver_override_recommended",
                                txPackets = 3,
                                txBytes = 4_000,
                                rxPackets = 5,
                                rxBytes = 6_000,
                                createdAt = 20L,
                            ),
                        )
                    contextsState.value =
                        listOf(
                            context(
                                id = "context-1",
                                sessionId = null,
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

            val viewModel =
                createDiagnosticsViewModel(RuntimeEnvironment.getApplication(), manager, FakeAppSettingsRepository())
            val collector = backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(1, manager.initializeCalls)
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
            assertEquals("Support context", state.overview.contextSummary?.title)
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
            assertTrue(scan.runRawHint.orEmpty().contains("Command-line mode", ignoreCase = true))
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
                                        ScanReport.serializer(),
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
                                        ScanReport.serializer(),
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
                                requestJson = "{}",
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
                                        ScanReport.serializer(),
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
                                        ScanReport.serializer(),
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
                                requestJson = "{}",
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
                                        ScanReport.serializer(),
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
                                        ScanReport.serializer(),
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
                                                    recommendedProxyConfigJson = "{}",
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
                    tcpChainSteps =
                        listOf(
                            TcpChainStepModel(kind = TcpChainStepKind.TlsRec, marker = "extlen"),
                            TcpChainStepModel(
                                kind = TcpChainStepKind.HostFake,
                                marker = "endhost+8",
                                fakeHostTemplate = "googlevideo.com",
                            ),
                        ),
                    quicFakeProfile = "realistic_initial",
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
                                        ScanReport.serializer(),
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
            assertTrue(signature.any { it.label == "QUIC fake profile" && it.value == "Zapret compatibility" })
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
    ): ScanReport =
        ScanReport(
            sessionId = id,
            profileId = profileId,
            pathMode = pathMode,
            startedAt = 1L,
            finishedAt = 2L,
            summary = summary,
            results = probes,
        )

    private fun strategyProbeProfileRequest(
        json: Json,
        profileId: String = "automatic-probing",
        displayName: String = "Automatic probing",
        suiteId: String = "quick_v1",
    ): String =
        json.encodeToString(
            ScanRequest.serializer(),
            ScanRequest(
                profileId = profileId,
                displayName = displayName,
                pathMode = ScanPathMode.RAW_PATH,
                kind = ScanKind.STRATEGY_PROBE,
                domainTargets = emptyList(),
                quicTargets = listOf(QuicTarget(host = "example.org")),
                strategyProbe = StrategyProbeRequest(suiteId = suiteId),
            ),
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
                recommendedProxyConfigJson = "{}",
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
    ): ScanReport =
        ScanReport(
            sessionId = sessionId,
            profileId = profileId,
            pathMode = ScanPathMode.RAW_PATH,
            startedAt = 10L,
            finishedAt = 20L,
            summary = summary,
            results = results,
            strategyProbeReport =
                StrategyProbeReport(
                    suiteId = suiteId,
                    tcpCandidates = tcpCandidates,
                    quicCandidates = quicCandidates,
                    recommendation = recommendation,
                ),
        )

    private fun snapshot(
        id: String,
        sessionId: String?,
        transport: String = "wifi",
        cellularDetails: CellularNetworkDetails? = null,
    ): NetworkSnapshotEntity =
        NetworkSnapshotEntity(
            id = id,
            sessionId = sessionId,
            snapshotKind = "passive",
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
    ): DiagnosticContextEntity =
        DiagnosticContextEntity(
            id = id,
            sessionId = sessionId,
            contextKind = if (sessionId == null) "passive" else "post_scan",
            payloadJson =
                json.encodeToString(
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
}

private class FakeDiagnosticsManager(
    var detail: DiagnosticSessionDetail? = null,
    private val archiveFailure: Throwable? = null,
) : DiagnosticsManager {
    private val _progressState = MutableStateFlow<ScanProgress?>(null)
    val progressState: MutableStateFlow<ScanProgress?> = _progressState
    val profilesState = MutableStateFlow<List<DiagnosticProfileEntity>>(emptyList())
    val sessionsState = MutableStateFlow<List<ScanSessionEntity>>(emptyList())
    val snapshotsState = MutableStateFlow<List<NetworkSnapshotEntity>>(emptyList())
    val contextsState = MutableStateFlow<List<DiagnosticContextEntity>>(emptyList())
    val telemetryState = MutableStateFlow<List<TelemetrySampleEntity>>(emptyList())
    val nativeEventsState = MutableStateFlow<List<NativeSessionEventEntity>>(emptyList())
    val exportsState = MutableStateFlow<List<ExportRecordEntity>>(emptyList())
    val approachStatsState = MutableStateFlow<List<BypassApproachSummary>>(emptyList())
    var initializeCalls = 0
    var lastArchiveSessionId: String? = null
    var lastActiveProfileId: String? = null
    var keptResolverRecommendationSessionId: String? = null
    var savedResolverRecommendationSessionId: String? = null
    var strategySignatureOverride: BypassStrategySignature? = null

    override val activeScanProgress: StateFlow<ScanProgress?> = _progressState.asStateFlow()
    override val profiles: Flow<List<DiagnosticProfileEntity>> = profilesState
    override val sessions: Flow<List<ScanSessionEntity>> = sessionsState
    override val approachStats: Flow<List<BypassApproachSummary>> = approachStatsState
    override val snapshots: Flow<List<NetworkSnapshotEntity>> = snapshotsState
    override val contexts: Flow<List<DiagnosticContextEntity>> = contextsState
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

    override suspend fun loadApproachDetail(
        kind: BypassApproachKind,
        id: String,
    ): BypassApproachDetail =
        BypassApproachDetail(
            summary =
                approachStatsState.value.firstOrNull { it.approachId.kind == kind && it.approachId.value == id }
                    ?: sampleApproachSummary(kind = kind, id = id),
            strategySignature =
                strategySignatureOverride ?: BypassStrategySignature(
                    mode = "VPN",
                    configSource = "ui",
                    hostAutolearn = "enabled",
                    desyncMethod = "split",
                    chainSummary = "tcp: split(1)",
                    protocolToggles = listOf("HTTP", "HTTPS"),
                    tlsRecordSplitEnabled = true,
                    tlsRecordMarker = "extlen",
                    splitMarker = "1",
                    fakeSniMode = null,
                    fakeSniValue = null,
                    fakeTlsBaseMode = null,
                    fakeTlsMods = emptyList(),
                    fakeTlsSize = null,
                    fakeOffsetMarker = null,
                    routeGroup = "3",
                ),
            recentValidatedSessions = sessionsState.value.take(2),
            recentUsageSessions = emptyList(),
            commonProbeFailures = listOf("dns_blocked (2)"),
            recentFailureNotes = listOf("dns:example.org=blocked"),
        )

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

    override suspend fun keepResolverRecommendationForSession(sessionId: String) {
        keptResolverRecommendationSessionId = sessionId
    }

    override suspend fun saveResolverRecommendation(sessionId: String) {
        savedResolverRecommendationSessionId = sessionId
    }
}

private fun sampleApproachSummary(
    kind: BypassApproachKind,
    id: String,
): BypassApproachSummary =
    BypassApproachSummary(
        approachId = BypassApproachId(kind = kind, value = id),
        displayName = "VPN Split",
        secondaryLabel = "Strategy",
        verificationState = "validated",
        validatedScanCount = 3,
        validatedSuccessCount = 2,
        validatedSuccessRate = 0.66f,
        lastValidatedResult = "Latest report",
        usageCount = 4,
        totalRuntimeDurationMs = 30_000L,
        recentRuntimeHealth = BypassRuntimeHealthSummary(totalErrors = 1, routeChanges = 2, restartCount = 1),
        lastUsedAt = 42L,
        topFailureOutcomes = listOf("dns_blocked (1)"),
        outcomeBreakdown = listOf(BypassOutcomeBreakdown("dns", 2, 0, 1, "dns_blocked")),
    )
