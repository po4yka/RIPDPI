package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.toRipDpiRuntimeContext
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.diagnostics.DefaultNetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsScanExecutionCoordinatorTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `completed execution applies temporary override and remembers preferred path`() =
        runTest {
            val historyRepository = FakeDiagnosticsHistoryRepository()
            val timelineSource = DefaultDiagnosticsTimelineSource(historyRepository, json)
            val resolverOverrideStore = FakeResolverOverrideStore()
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN)
            val networkFingerprintProvider = FakeNetworkFingerprintProvider()
            val preferredPathStore = DefaultNetworkDnsPathPreferenceStore(historyRepository)
            val coordinator =
                DiagnosticsScanExecutionCoordinator(
                    context = TestContext(),
                    historyRepository = historyRepository,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    networkFingerprintProvider = networkFingerprintProvider,
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    serviceStateStore = serviceStateStore,
                    resolverOverrideStore = resolverOverrideStore,
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(historyRepository),
                    networkDnsPathPreferenceStore = preferredPathStore,
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-1",
                    settings =
                        defaultDiagnosticsAppSettings()
                            .toBuilder()
                            .setDnsMode(DnsModePlainUdp)
                            .setDnsIp("8.8.8.8")
                            .build(),
                    exposeProgress = true,
                    networkFingerprint = networkFingerprintProvider.capture(),
                )
            seedPreparedScan(historyRepository, prepared)
            val runtimeState = DiagnosticsScanRuntimeState(timelineSource).apply { rememberPreparedScan(prepared) }
            val bridge =
                FakeNetworkDiagnosticsBridge(json).apply {
                    autoCompleteOnStart = false
                    enqueuePassiveEvents(
                        json.encodeToString(
                            ListSerializer(NativeSessionEvent.serializer()),
                            listOf(
                                NativeSessionEvent(
                                    source = "native",
                                    level = "warn",
                                    message = "probe warn",
                                    createdAt = 15L,
                                ),
                            ),
                        ),
                    )
                    enqueueProgress(
                        ScanProgress(
                            sessionId = prepared.sessionId,
                            phase = "running",
                            completedSteps = 1,
                            totalSteps = 2,
                            message = "running",
                        ),
                    )
                    enqueueProgress(
                        ScanProgress(
                            sessionId = prepared.sessionId,
                            phase = "complete",
                            completedSteps = 2,
                            totalSteps = 2,
                            message = "complete",
                            isFinished = true,
                        ),
                    )
                    enqueueReport(scanReportWithResolverRecommendation(prepared.sessionId))
                }

            coordinator.execute(prepared, bridge, rawPathRunner = { block -> block() }, runtimeState = runtimeState)

            val session = requireNotNull(historyRepository.getScanSession(prepared.sessionId))
            val preferredPath =
                historyRepository.getNetworkDnsPathPreference(networkFingerprintProvider.capture().scopeKey())
            val persistedReport =
                diagnosticsTestJson().decodeFromString(
                    ScanReport.serializer(),
                    requireNotNull(session.reportJson),
                )

            assertEquals("completed", session.status)
            assertTrue(requireNotNull(persistedReport.resolverRecommendation).appliedTemporarily)
            assertEquals("cloudflare", resolverOverrideStore.override.value?.resolverId)
            assertNotNull(preferredPath)
            assertEquals(1, historyRepository.storedProbeResults(prepared.sessionId).size)
            assertEquals(2, historyRepository.snapshotsState.value.count { it.sessionId == prepared.sessionId })
            assertEquals(2, historyRepository.contextsState.value.count { it.sessionId == prepared.sessionId })
            assertTrue(historyRepository.nativeEventsState.value.any { it.sessionId == prepared.sessionId })
            assertNull(timelineSource.activeScanProgress.value)
        }

    @Test
    fun `hidden execution never surfaces active progress`() =
        runTest {
            val historyRepository = FakeDiagnosticsHistoryRepository()
            val timelineSource = DefaultDiagnosticsTimelineSource(historyRepository, json)
            val coordinator =
                DiagnosticsScanExecutionCoordinator(
                    context = TestContext(),
                    historyRepository = historyRepository,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
                    resolverOverrideStore = FakeResolverOverrideStore(),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(historyRepository),
                    networkDnsPathPreferenceStore = DefaultNetworkDnsPathPreferenceStore(historyRepository),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-hidden",
                    settings = defaultDiagnosticsAppSettings(),
                    exposeProgress = false,
                    registerActiveBridge = false,
                )
            seedPreparedScan(historyRepository, prepared)
            val runtimeState = DiagnosticsScanRuntimeState(timelineSource)
            val bridge =
                FakeNetworkDiagnosticsBridge(json).apply {
                    autoCompleteOnStart = false
                    enqueueProgress(
                        ScanProgress(
                            sessionId = prepared.sessionId,
                            phase = "complete",
                            completedSteps = 1,
                            totalSteps = 1,
                            message = "complete",
                            isFinished = true,
                        ),
                    )
                    enqueueReport(scanReportWithResolverRecommendation(prepared.sessionId))
                }
            val progressHistory = mutableListOf<ScanProgress?>()
            val collectionJob = backgroundScope.launch { timelineSource.activeScanProgress.collect { progressHistory += it } }

            coordinator.execute(prepared, bridge, rawPathRunner = { block -> block() }, runtimeState = runtimeState)
            advanceUntilIdle()
            collectionJob.cancel()

            assertTrue(progressHistory.all { it == null })
        }

    @Test
    fun `missing finished report marks session failed`() =
        runTest {
            val historyRepository = FakeDiagnosticsHistoryRepository()
            val timelineSource = DefaultDiagnosticsTimelineSource(historyRepository, json)
            val coordinator =
                DiagnosticsScanExecutionCoordinator(
                    context = TestContext(),
                    historyRepository = historyRepository,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
                    resolverOverrideStore = FakeResolverOverrideStore(),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(historyRepository),
                    networkDnsPathPreferenceStore = DefaultNetworkDnsPathPreferenceStore(historyRepository),
                    json = json,
                )
            val prepared = preparedDiagnosticsScan(sessionId = "session-failed", settings = defaultDiagnosticsAppSettings())
            seedPreparedScan(historyRepository, prepared)
            val runtimeState = DiagnosticsScanRuntimeState(timelineSource)
            val bridge =
                FakeNetworkDiagnosticsBridge(json).apply {
                    autoCompleteOnStart = false
                    enqueueProgress(
                        ScanProgress(
                            sessionId = prepared.sessionId,
                            phase = "complete",
                            completedSteps = 1,
                            totalSteps = 1,
                            message = "complete",
                            isFinished = true,
                        ),
                    )
                }

            coordinator.execute(prepared, bridge, rawPathRunner = { block -> block() }, runtimeState = runtimeState)

            val failedSession = historyRepository.getScanSession(prepared.sessionId)
            assertEquals("failed", failedSession?.status)
            assertTrue(requireNotNull(failedSession?.summary).contains("without a report"))
            assertEquals(1, bridge.destroyCount)
            assertNull(timelineSource.activeScanProgress.value)
        }

    @Test
    fun `strategy probe completion remembers validated network policy`() =
        runTest {
            val historyRepository = FakeDiagnosticsHistoryRepository()
            val timelineSource = DefaultDiagnosticsTimelineSource(historyRepository, json)
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN)
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val networkFingerprintProvider = FakeNetworkFingerprintProvider()
            val coordinator =
                DiagnosticsScanExecutionCoordinator(
                    context = TestContext(),
                    historyRepository = historyRepository,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    networkFingerprintProvider = networkFingerprintProvider,
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    serviceStateStore = serviceStateStore,
                    resolverOverrideStore = FakeResolverOverrideStore(),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(historyRepository),
                    networkDnsPathPreferenceStore = DefaultNetworkDnsPathPreferenceStore(historyRepository),
                    json = json,
                )
            val prepared = preparedDiagnosticsScan(sessionId = "session-strategy", settings = settings)
            seedPreparedScan(historyRepository, prepared)
            val runtimeState = DiagnosticsScanRuntimeState(timelineSource)
            val bridge =
                FakeNetworkDiagnosticsBridge(json).apply {
                    autoCompleteOnStart = false
                    enqueueProgress(
                        ScanProgress(
                            sessionId = prepared.sessionId,
                            phase = "complete",
                            completedSteps = 1,
                            totalSteps = 1,
                            message = "complete",
                            isFinished = true,
                        ),
                    )
                    enqueueReport(scanReportWithStrategyProbe(prepared.sessionId, settings))
                }

            coordinator.execute(prepared, bridge, rawPathRunner = { block -> block() }, runtimeState = runtimeState)

            assertFalse(historyRepository.rememberedPoliciesState.value.isEmpty())
            assertEquals("validated", historyRepository.rememberedPoliciesState.value.single().status)
        }
}

private suspend fun preparedDiagnosticsScan(
    sessionId: String,
    settings: com.poyka.ripdpi.proto.AppSettings,
    exposeProgress: Boolean = true,
    registerActiveBridge: Boolean = true,
    networkFingerprint: com.poyka.ripdpi.data.NetworkFingerprint? = null,
) = PreparedDiagnosticsScan(
    sessionId = sessionId,
    settings = settings,
    pathMode = ScanPathMode.RAW_PATH,
    requestJson = "{}",
    exposeProgress = exposeProgress,
    registerActiveBridge = registerActiveBridge,
    networkFingerprint = networkFingerprint,
    preferredDnsPath = null,
    initialSession =
        diagnosticsSession(
            id = sessionId,
            profileId = "default",
            pathMode = ScanPathMode.RAW_PATH.name,
            summary = "running",
            status = "running",
            reportJson = null,
        ),
    preScanSnapshot =
        com.poyka.ripdpi.data.diagnostics.NetworkSnapshotEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            snapshotKind = "pre_scan",
            payloadJson = diagnosticsTestJson().encodeToString(NetworkSnapshotModel.serializer(), networkSnapshotModelForTest()),
            capturedAt = 10L,
        ),
    preScanContext =
        com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            contextKind = "pre_scan",
            payloadJson = diagnosticsTestJson().encodeToString(DiagnosticContextModel.serializer(), FakeDiagnosticsContextProvider().captureContextForTest()),
            capturedAt = 10L,
        ),
)

private suspend fun seedPreparedScan(
    historyRepository: FakeDiagnosticsHistoryRepository,
    prepared: PreparedDiagnosticsScan,
) {
    historyRepository.upsertScanSession(prepared.initialSession)
    historyRepository.upsertSnapshot(prepared.preScanSnapshot)
    historyRepository.upsertContextSnapshot(prepared.preScanContext)
}

private fun scanReportWithResolverRecommendation(sessionId: String) =
    ScanReport(
        sessionId = sessionId,
        profileId = "default",
        pathMode = ScanPathMode.RAW_PATH,
        startedAt = 10L,
        finishedAt = 20L,
        summary = "resolver recommendation",
        results =
            listOf(
                ProbeResult(
                    probeType = "dns_integrity",
                    target = "blocked.example",
                    outcome = "dns_substitution",
                    details =
                        listOf(
                            ProbeDetail("encryptedResolverId", com.poyka.ripdpi.data.DnsProviderCloudflare),
                            ProbeDetail("encryptedProtocol", com.poyka.ripdpi.data.EncryptedDnsProtocolDoh),
                            ProbeDetail("encryptedAddresses", "1.1.1.1"),
                            ProbeDetail("encryptedBootstrapValidated", "true"),
                            ProbeDetail("encryptedLatencyMs", "32"),
                        ),
                ),
            ),
    )

private fun scanReportWithStrategyProbe(
    sessionId: String,
    settings: com.poyka.ripdpi.proto.AppSettings,
): ScanReport {
    val proxyConfigJson =
        RipDpiProxyUIPreferences(
            settings = settings,
            hostAutolearnStorePath = null,
            runtimeContext = settings.activeDnsSettings().toRipDpiRuntimeContext(),
        ).toNativeConfigJson()
    return ScanReport(
        sessionId = sessionId,
        profileId = "automatic-probing",
        pathMode = ScanPathMode.RAW_PATH,
        startedAt = 10L,
        finishedAt = 20L,
        summary = "strategy probe",
        results =
            listOf(
                ProbeResult(
                    probeType = "http",
                    target = "example.org",
                    outcome = "success",
                ),
            ),
        strategyProbeReport =
            StrategyProbeReport(
                suiteId = "quick_v1",
                tcpCandidates =
                    listOf(
                        StrategyProbeCandidateSummary(
                            id = "tcp-1",
                            label = "TCP candidate",
                            family = "split",
                            outcome = "success",
                            rationale = "best",
                            succeededTargets = 1,
                            totalTargets = 1,
                            weightedSuccessScore = 10,
                            totalWeight = 10,
                            qualityScore = 10,
                        ),
                    ),
                quicCandidates =
                    listOf(
                        StrategyProbeCandidateSummary(
                            id = "quic-1",
                            label = "QUIC candidate",
                            family = "quic_burst",
                            outcome = "success",
                            rationale = "best",
                            succeededTargets = 1,
                            totalTargets = 1,
                            weightedSuccessScore = 10,
                            totalWeight = 10,
                            qualityScore = 10,
                        ),
                    ),
                recommendation =
                    StrategyProbeRecommendation(
                        tcpCandidateId = "tcp-1",
                        tcpCandidateLabel = "TCP candidate",
                        quicCandidateId = "quic-1",
                        quicCandidateLabel = "QUIC candidate",
                        rationale = "best path",
                        recommendedProxyConfigJson = proxyConfigJson,
                    ),
            ),
    )
}
