package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiChainConfig
import com.poyka.ripdpi.core.RipDpiProtocolConfig
import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiQuicConfig
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.diagnostics.DefaultNetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.decodedSource
import com.poyka.ripdpi.diagnostics.domain.DiagnosticsIntent
import com.poyka.ripdpi.diagnostics.domain.ExecutionPolicy
import com.poyka.ripdpi.diagnostics.domain.ScanContext
import com.poyka.ripdpi.diagnostics.domain.ScanPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = timelineSource(stores, backgroundScope)
            val resolverOverrideStore = FakeResolverOverrideStore()
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN)
            val networkFingerprintProvider = FakeNetworkFingerprintProvider()
            val preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock)
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    resolverOverrideStore = resolverOverrideStore,
                    networkFingerprintProvider = networkFingerprintProvider,
                    preferredPathStore = preferredPathStore,
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
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
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
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
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

            val session = requireNotNull(stores.getScanSession(prepared.sessionId))
            val preferredPath =
                stores.getNetworkDnsPathPreference(networkFingerprintProvider.capture().scopeKey())
            val persistedReport =
                diagnosticsTestJson().decodeFromString(
                    ScanReport.serializer(),
                    requireNotNull(session.reportJson),
                )

            assertEquals("completed", session.status)
            assertTrue(requireNotNull(persistedReport.resolverRecommendation).appliedTemporarily)
            assertEquals("cloudflare", resolverOverrideStore.override.value?.resolverId)
            assertNotNull(preferredPath)
            assertEquals(1, stores.storedProbeResults(prepared.sessionId).size)
            assertEquals(2, stores.snapshotsState.value.count { it.sessionId == prepared.sessionId })
            assertEquals(2, stores.contextsState.value.count { it.sessionId == prepared.sessionId })
            assertTrue(stores.nativeEventsState.value.any { it.sessionId == prepared.sessionId })
            assertNull(timelineSource.activeScanProgress.value)
        }

    @Test
    fun `hidden execution never surfaces active progress`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = timelineSource(stores, backgroundScope)
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-hidden",
                    settings = defaultDiagnosticsAppSettings(),
                    exposeProgress = false,
                    registerActiveBridge = false,
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
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
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val progressHistory = mutableListOf<ScanProgress?>()
            val collectionJob =
                backgroundScope.launch {
                    timelineSource.activeScanProgress.collect {
                        progressHistory +=
                            it
                    }
                }

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })
            advanceUntilIdle()
            collectionJob.cancel()

            assertTrue(progressHistory.all { it == null })
        }

    @Test
    fun `missing finished report marks session failed`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = timelineSource(stores, backgroundScope)
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(sessionId = "session-failed", settings = defaultDiagnosticsAppSettings())
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
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
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

            val failedSession = stores.getScanSession(prepared.sessionId)
            assertEquals("failed", failedSession?.status)
            assertTrue(requireNotNull(failedSession?.summary).contains("without a report"))
            assertEquals(1, bridge.destroyCount)
            assertNull(timelineSource.activeScanProgress.value)
        }

    @Test
    fun `delayed finished report is tolerated after finished progress`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = timelineSource(stores, backgroundScope)
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN),
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-delayed-report",
                    settings = defaultDiagnosticsAppSettings(),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
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
                    repeat(8) {
                        enqueueReport(null)
                    }
                    enqueueReport(scanReportWithResolverRecommendation(prepared.sessionId))
                }
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

            val session = requireNotNull(stores.getScanSession(prepared.sessionId))
            assertEquals("completed", session.status)
            assertNotNull(session.reportJson)
        }

    @Test
    fun `background automatic probing remembers validated network policy`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = timelineSource(stores, backgroundScope)
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN)
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val preparedFingerprint = strategyProbeFingerprint(ssid = "network-default", gateway = "192.0.2.1")
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    networkFingerprintProvider = MutableNetworkFingerprintProvider(preparedFingerprint),
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-strategy",
                    settings = settings,
                    scanOrigin = DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND,
                    exposeProgress = false,
                    registerActiveBridge = false,
                    networkFingerprint = preparedFingerprint,
                    profileId = "automatic-probing",
                    family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                    kind = ScanKind.STRATEGY_PROBE,
                    strategyProbeRequest = StrategyProbeRequest(suiteId = "quick_v1"),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
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
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

            assertFalse(stores.rememberedPoliciesState.value.isEmpty())
            assertEquals(
                "validated",
                stores.rememberedPoliciesState.value
                    .single()
                    .status,
            )
            assertEquals(
                preparedFingerprint.scopeKey(),
                stores.rememberedPoliciesState.value
                    .single()
                    .fingerprintHash,
            )
        }

    @Test
    fun `background automatic probing skips remembered policy when audit assessment is missing`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = timelineSource(stores, backgroundScope)
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN)
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val preparedFingerprint = strategyProbeFingerprint(ssid = "network-missing-audit", gateway = "192.0.2.31")
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    networkFingerprintProvider = MutableNetworkFingerprintProvider(preparedFingerprint),
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-strategy-missing-audit",
                    settings = settings,
                    scanOrigin = DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND,
                    exposeProgress = false,
                    registerActiveBridge = false,
                    networkFingerprint = preparedFingerprint,
                    profileId = "automatic-probing",
                    family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                    kind = ScanKind.STRATEGY_PROBE,
                    strategyProbeRequest = StrategyProbeRequest(suiteId = "quick_v1"),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
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
                    enqueueReport(
                        scanReportWithStrategyProbe(
                            sessionId = prepared.sessionId,
                            settings = settings,
                            auditAssessment = null,
                        ),
                    )
                }
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

            assertTrue(stores.rememberedPoliciesState.value.isEmpty())
        }

    @Test
    fun `background automatic probing skips remembered policy when confidence is medium`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = timelineSource(stores, backgroundScope)
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN)
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val preparedFingerprint =
                strategyProbeFingerprint(ssid = "network-medium-confidence", gateway = "192.0.2.32")
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    networkFingerprintProvider = MutableNetworkFingerprintProvider(preparedFingerprint),
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-strategy-medium-confidence",
                    settings = settings,
                    scanOrigin = DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND,
                    exposeProgress = false,
                    registerActiveBridge = false,
                    networkFingerprint = preparedFingerprint,
                    profileId = "automatic-probing",
                    family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                    kind = ScanKind.STRATEGY_PROBE,
                    strategyProbeRequest = StrategyProbeRequest(suiteId = "quick_v1"),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
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
                    enqueueReport(
                        scanReportWithStrategyProbe(
                            sessionId = prepared.sessionId,
                            settings = settings,
                            auditAssessment =
                                strategyProbeAuditAssessmentForCoordinator(
                                    confidenceLevel = StrategyProbeAuditConfidenceLevel.MEDIUM,
                                ),
                        ),
                    )
                }
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

            assertTrue(stores.rememberedPoliciesState.value.isEmpty())
        }

    @Test
    fun `background automatic probing skips remembered policy when coverage is insufficient`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = timelineSource(stores, backgroundScope)
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN)
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val preparedFingerprint = strategyProbeFingerprint(ssid = "network-low-coverage", gateway = "192.0.2.33")
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    networkFingerprintProvider = MutableNetworkFingerprintProvider(preparedFingerprint),
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-strategy-low-coverage",
                    settings = settings,
                    scanOrigin = DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND,
                    exposeProgress = false,
                    registerActiveBridge = false,
                    networkFingerprint = preparedFingerprint,
                    profileId = "automatic-probing",
                    family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                    kind = ScanKind.STRATEGY_PROBE,
                    strategyProbeRequest = StrategyProbeRequest(suiteId = "quick_v1"),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
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
                    enqueueReport(
                        scanReportWithStrategyProbe(
                            sessionId = prepared.sessionId,
                            settings = settings,
                            auditAssessment =
                                strategyProbeAuditAssessmentForCoordinator(
                                    matrixCoveragePercent = 74,
                                ),
                        ),
                    )
                }
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

            assertTrue(stores.rememberedPoliciesState.value.isEmpty())
        }

    @Test
    fun `manual automatic probing does not remember validated network policy`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = timelineSource(stores, backgroundScope)
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN)
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val preparedFingerprint = strategyProbeFingerprint(ssid = "network-manual", gateway = "192.0.2.11")
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    networkFingerprintProvider = MutableNetworkFingerprintProvider(preparedFingerprint),
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-manual-probe",
                    settings = settings,
                    scanOrigin = DiagnosticsScanOrigin.USER_INITIATED,
                    networkFingerprint = preparedFingerprint,
                    profileId = "automatic-probing",
                    family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                    kind = ScanKind.STRATEGY_PROBE,
                    strategyProbeRequest = StrategyProbeRequest(suiteId = "quick_v1"),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
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
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

            assertTrue(stores.rememberedPoliciesState.value.isEmpty())
        }

    @Test
    fun `manual automatic audit does not remember validated network policy`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = timelineSource(stores, backgroundScope)
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN)
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val preparedFingerprint = strategyProbeFingerprint(ssid = "network-audit", gateway = "192.0.2.12")
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    networkFingerprintProvider = MutableNetworkFingerprintProvider(preparedFingerprint),
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-manual-audit",
                    settings = settings,
                    scanOrigin = DiagnosticsScanOrigin.USER_INITIATED,
                    networkFingerprint = preparedFingerprint,
                    profileId = "automatic-audit",
                    family = DiagnosticProfileFamily.AUTOMATIC_AUDIT,
                    kind = ScanKind.STRATEGY_PROBE,
                    strategyProbeRequest = StrategyProbeRequest(suiteId = "full_matrix_v1"),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
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
                    enqueueReport(
                        scanReportWithStrategyProbe(
                            sessionId = prepared.sessionId,
                            settings = settings,
                            profileId = "automatic-audit",
                            suiteId = "full_matrix_v1",
                        ),
                    )
                }
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

            assertTrue(stores.rememberedPoliciesState.value.isEmpty())
        }

    @Test
    fun `manual strategy probe with always persistence policy remembers validated network policy`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = timelineSource(stores, backgroundScope)
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN)
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val preparedFingerprint = strategyProbeFingerprint(ssid = "network-always", gateway = "192.0.2.21")
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    networkFingerprintProvider = MutableNetworkFingerprintProvider(preparedFingerprint),
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-always-persist",
                    settings = settings,
                    scanOrigin = DiagnosticsScanOrigin.USER_INITIATED,
                    networkFingerprint = preparedFingerprint,
                    profileId = "custom-probe",
                    family = DiagnosticProfileFamily.GENERAL,
                    kind = ScanKind.STRATEGY_PROBE,
                    strategyProbeRequest = StrategyProbeRequest(suiteId = "quick_v1"),
                    probePersistencePolicy = ProbePersistencePolicy.ALWAYS,
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
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
                    enqueueReport(
                        scanReportWithStrategyProbe(
                            sessionId = prepared.sessionId,
                            settings = settings,
                            profileId = "custom-probe",
                        ),
                    )
                }
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

            assertEquals(1, stores.rememberedPoliciesState.value.size)
            assertEquals(
                preparedFingerprint.scopeKey(),
                stores.rememberedPoliciesState.value
                    .single()
                    .fingerprintHash,
            )
            assertEquals(
                RememberedNetworkPolicySource.AUTOMATIC_PROBING_BACKGROUND,
                stores.rememberedPoliciesState.value
                    .single()
                    .decodedSource(),
            )
        }

    @Test
    fun `background automatic probing keeps prepared fingerprint when provider changes`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = timelineSource(stores, backgroundScope)
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN)
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val preparedFingerprint = strategyProbeFingerprint(ssid = "network-a", gateway = "192.0.2.10")
            val changedFingerprint = strategyProbeFingerprint(ssid = "network-b", gateway = "192.0.2.20")
            val networkFingerprintProvider = MutableNetworkFingerprintProvider(preparedFingerprint)
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    networkFingerprintProvider = networkFingerprintProvider,
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-strategy-fingerprint",
                    settings = settings,
                    scanOrigin = DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND,
                    exposeProgress = false,
                    registerActiveBridge = false,
                    networkFingerprint = preparedFingerprint,
                    profileId = "automatic-probing",
                    family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                    kind = ScanKind.STRATEGY_PROBE,
                    strategyProbeRequest = StrategyProbeRequest(suiteId = "quick_v1"),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
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
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)
            networkFingerprintProvider.fingerprint = changedFingerprint

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

            val rememberedPolicy = stores.rememberedPoliciesState.value.single()
            assertEquals(preparedFingerprint.scopeKey(), rememberedPolicy.fingerprintHash)
            assertNotEquals(changedFingerprint.scopeKey(), rememberedPolicy.fingerprintHash)
        }

    @Test
    fun `background automatic probing skips remembered policy when prepared fingerprint is missing`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = timelineSource(stores, backgroundScope)
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN)
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val fixtures =
                executionCoordinatorFixtures(
                    stores = stores,
                    timelineSource = timelineSource,
                    serviceStateStore = serviceStateStore,
                    preferredPathStore = DefaultNetworkDnsPathPreferenceStore(stores, clock),
                    rememberedNetworkPolicyStore = DefaultRememberedNetworkPolicyStore(stores, clock),
                    json = json,
                )
            val prepared =
                preparedDiagnosticsScan(
                    sessionId = "session-strategy-no-fingerprint",
                    settings = settings,
                    scanOrigin = DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND,
                    exposeProgress = false,
                    registerActiveBridge = false,
                    networkFingerprint = null,
                    profileId = "automatic-probing",
                    family = DiagnosticProfileFamily.AUTOMATIC_PROBING,
                    kind = ScanKind.STRATEGY_PROBE,
                    strategyProbeRequest = StrategyProbeRequest(suiteId = "quick_v1"),
                )
            seedPreparedScan(stores, prepared)
            fixtures.activeScanRegistry.rememberPreparedScan(prepared)
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
            fixtures.activeScanRegistry.registerBridge(bridge, prepared.sessionId, prepared.registerActiveBridge)
            val handle = BridgeSessionHandle(bridge, prepared.sessionId, prepared.registerActiveBridge)

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

            assertTrue(stores.rememberedPoliciesState.value.isEmpty())
        }

    private fun timelineSource(
        stores: FakeDiagnosticsHistoryStores,
        scope: CoroutineScope,
    ): DefaultDiagnosticsTimelineSource =
        DefaultDiagnosticsTimelineSource(
            profileCatalog = stores,
            scanRecordStore = stores,
            artifactReadStore = stores,
            bypassUsageHistoryStore = stores,
            mapper = DiagnosticsBoundaryMapper(json),
            scope = scope,
            json = json,
        )
}

private data class ExecutionCoordinatorFixtures(
    val coordinator: DiagnosticsScanExecutionCoordinator,
    val activeScanRegistry: ActiveScanRegistry,
)

private fun executionCoordinatorFixtures(
    stores: FakeDiagnosticsHistoryStores,
    timelineSource: DefaultDiagnosticsTimelineSource,
    serviceStateStore: FakeServiceStateStore,
    resolverOverrideStore: FakeResolverOverrideStore = FakeResolverOverrideStore(),
    networkFingerprintProvider: com.poyka.ripdpi.data.NetworkFingerprintProvider = FakeNetworkFingerprintProvider(),
    preferredPathStore: DefaultNetworkDnsPathPreferenceStore =
        DefaultNetworkDnsPathPreferenceStore(stores, TestDiagnosticsHistoryClock()),
    rememberedNetworkPolicyStore: DefaultRememberedNetworkPolicyStore =
        DefaultRememberedNetworkPolicyStore(stores, TestDiagnosticsHistoryClock()),
    json: kotlinx.serialization.json.Json = diagnosticsTestJson(),
): ExecutionCoordinatorFixtures {
    val activeScanRegistry = ActiveScanRegistry(timelineSource)
    val bridgeExecutionService =
        BridgeExecutionService(
            networkDiagnosticsBridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json),
            activeScanRegistry = activeScanRegistry,
        )
    val passiveEventPersistenceService = PassiveEventPersistenceService(stores, json)
    val scanFinalizationService =
        ScanFinalizationService(
            context = TestContext(),
            scanRecordStore = stores,
            artifactWriteStore = stores,
            networkMetadataProvider = FakeNetworkMetadataProvider(),
            networkFingerprintProvider = networkFingerprintProvider,
            diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
            serviceStateStore = serviceStateStore,
            resolverOverrideStore = resolverOverrideStore,
            rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
            networkDnsPathPreferenceStore = preferredPathStore,
            findingProjector = DiagnosticsFindingProjector(),
            json = json,
        )
    return ExecutionCoordinatorFixtures(
        coordinator =
            DiagnosticsScanExecutionCoordinator(
                scanRecordStore = stores,
                activeScanRegistry = activeScanRegistry,
                bridgeExecutionService = bridgeExecutionService,
                bridgePollingService = BridgePollingService(passiveEventPersistenceService, json),
                scanFinalizationService = scanFinalizationService,
            ),
        activeScanRegistry = activeScanRegistry,
    )
}

@Suppress("LongMethod")
private suspend fun preparedDiagnosticsScan(
    sessionId: String,
    settings: com.poyka.ripdpi.proto.AppSettings,
    scanOrigin: DiagnosticsScanOrigin = DiagnosticsScanOrigin.USER_INITIATED,
    exposeProgress: Boolean = true,
    registerActiveBridge: Boolean = true,
    networkFingerprint: com.poyka.ripdpi.data.NetworkFingerprint? = null,
    profileId: String = "default",
    family: DiagnosticProfileFamily = DiagnosticProfileFamily.GENERAL,
    kind: ScanKind = ScanKind.CONNECTIVITY,
    strategyProbeRequest: StrategyProbeRequest? = null,
    probePersistencePolicy: ProbePersistencePolicy? = null,
) = PreparedDiagnosticsScan(
    sessionId = sessionId,
    settings = settings,
    pathMode = ScanPathMode.RAW_PATH,
    intent =
        DiagnosticsIntent(
            profileId = profileId,
            displayName = "Diagnostics",
            settings = settings,
            kind = kind,
            family = family,
            regionTag = null,
            executionPolicy =
                ExecutionPolicy(
                    manualOnly = false,
                    allowBackground = scanOrigin == DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND,
                    requiresRawPath = kind == ScanKind.STRATEGY_PROBE,
                    probePersistencePolicy =
                        probePersistencePolicy
                            ?: if (kind == ScanKind.STRATEGY_PROBE &&
                                family == DiagnosticProfileFamily.AUTOMATIC_PROBING
                            ) {
                                ProbePersistencePolicy.BACKGROUND_ONLY
                            } else {
                                ProbePersistencePolicy.MANUAL_ONLY
                            },
                ),
            packRefs = emptyList(),
            domainTargets = emptyList(),
            dnsTargets = emptyList(),
            tcpTargets = emptyList(),
            quicTargets = emptyList(),
            serviceTargets = emptyList(),
            circumventionTargets = emptyList(),
            throughputTargets = emptyList(),
            whitelistSni = emptyList(),
            telegramTarget = null,
            strategyProbe = strategyProbeRequest,
            requestedPathMode = ScanPathMode.RAW_PATH,
        ),
    context =
        ScanContext(
            settings = settings,
            pathMode = ScanPathMode.RAW_PATH,
            networkFingerprint = networkFingerprint,
            preferredDnsPath = null,
            networkSnapshot = null,
            serviceMode = Mode.VPN.name,
            contextSnapshot = FakeDiagnosticsContextProvider().captureContextForTest(),
            approachSnapshot =
                createStoredApproachSnapshot(
                    diagnosticsTestJson(),
                    settings,
                    null,
                    FakeDiagnosticsContextProvider().captureContextForTest(),
                ),
        ),
    plan =
        ScanPlan(
            intent =
                DiagnosticsIntent(
                    profileId = profileId,
                    displayName = "Diagnostics",
                    settings = settings,
                    kind = kind,
                    family = family,
                    regionTag = null,
                    executionPolicy =
                        ExecutionPolicy(
                            manualOnly = false,
                            allowBackground = scanOrigin == DiagnosticsScanOrigin.AUTOMATIC_BACKGROUND,
                            requiresRawPath = kind == ScanKind.STRATEGY_PROBE,
                            probePersistencePolicy =
                                probePersistencePolicy
                                    ?: if (kind == ScanKind.STRATEGY_PROBE &&
                                        family == DiagnosticProfileFamily.AUTOMATIC_PROBING
                                    ) {
                                        ProbePersistencePolicy.BACKGROUND_ONLY
                                    } else {
                                        ProbePersistencePolicy.MANUAL_ONLY
                                    },
                        ),
                    packRefs = emptyList(),
                    domainTargets = emptyList(),
                    dnsTargets = emptyList(),
                    tcpTargets = emptyList(),
                    quicTargets = emptyList(),
                    serviceTargets = emptyList(),
                    circumventionTargets = emptyList(),
                    throughputTargets = emptyList(),
                    whitelistSni = emptyList(),
                    telegramTarget = null,
                    strategyProbe = strategyProbeRequest,
                    requestedPathMode = ScanPathMode.RAW_PATH,
                ),
            context =
                ScanContext(
                    settings = settings,
                    pathMode = ScanPathMode.RAW_PATH,
                    networkFingerprint = networkFingerprint,
                    preferredDnsPath = null,
                    networkSnapshot = null,
                    serviceMode = Mode.VPN.name,
                    contextSnapshot = FakeDiagnosticsContextProvider().captureContextForTest(),
                    approachSnapshot =
                        createStoredApproachSnapshot(
                            diagnosticsTestJson(),
                            settings,
                            null,
                            FakeDiagnosticsContextProvider().captureContextForTest(),
                        ),
                ),
            proxyHost = null,
            proxyPort = null,
            dnsTargets = emptyList(),
            probeTasks = emptyList(),
        ),
    requestJson = "{}",
    scanOrigin = scanOrigin,
    launchTrigger = null,
    exposeProgress = exposeProgress,
    registerActiveBridge = registerActiveBridge,
    networkFingerprint = networkFingerprint,
    preferredDnsPath = null,
    initialSession =
        diagnosticsSession(
            id = sessionId,
            profileId = profileId,
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
            payloadJson =
                diagnosticsTestJson().encodeToString(
                    NetworkSnapshotModel.serializer(),
                    networkSnapshotModelForTest(),
                ),
            capturedAt = 10L,
        ),
    preScanContext =
        com.poyka.ripdpi.data.diagnostics.DiagnosticContextEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            contextKind = "pre_scan",
            payloadJson =
                diagnosticsTestJson().encodeToString(
                    DiagnosticContextModel.serializer(),
                    FakeDiagnosticsContextProvider().captureContextForTest(),
                ),
            capturedAt = 10L,
        ),
)

private suspend fun seedPreparedScan(
    stores: FakeDiagnosticsHistoryStores,
    prepared: PreparedDiagnosticsScan,
) {
    stores.upsertScanSession(prepared.initialSession)
    stores.upsertSnapshot(prepared.preScanSnapshot)
    stores.upsertContextSnapshot(prepared.preScanContext)
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
    profileId: String = "automatic-probing",
    suiteId: String = "quick_v1",
    auditAssessment: StrategyProbeAuditAssessment? = strategyProbeAuditAssessmentForCoordinator(),
    tcpSucceededTargets: Int = 1,
    quicSucceededTargets: Int = 1,
): ScanReport =
    ScanReport(
        sessionId = sessionId,
        profileId = profileId,
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
                suiteId = suiteId,
                tcpCandidates =
                    listOf(
                        StrategyProbeCandidateSummary(
                            id = "tcp-1",
                            label = "TCP candidate",
                            family = "hostfake",
                            outcome = "success",
                            rationale = "best",
                            succeededTargets = tcpSucceededTargets,
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
                            family = "quic_realistic_burst",
                            outcome = "success",
                            rationale = "best",
                            succeededTargets = quicSucceededTargets,
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
                        recommendedProxyConfigJson = validRecommendedProxyConfigJsonForCoordinator(),
                    ),
                auditAssessment = auditAssessment,
            ),
    )

private fun strategyProbeAuditAssessmentForCoordinator(
    confidenceLevel: StrategyProbeAuditConfidenceLevel = StrategyProbeAuditConfidenceLevel.HIGH,
    matrixCoveragePercent: Int = 100,
    winnerCoveragePercent: Int = 100,
): StrategyProbeAuditAssessment =
    StrategyProbeAuditAssessment(
        dnsShortCircuited = false,
        coverage =
            StrategyProbeAuditCoverage(
                tcpCandidatesPlanned = 2,
                tcpCandidatesExecuted = 2,
                tcpCandidatesSkipped = 0,
                tcpCandidatesNotApplicable = 0,
                quicCandidatesPlanned = 2,
                quicCandidatesExecuted = 2,
                quicCandidatesSkipped = 0,
                quicCandidatesNotApplicable = 0,
                tcpWinnerSucceededTargets = 1,
                tcpWinnerTotalTargets = 1,
                quicWinnerSucceededTargets = 1,
                quicWinnerTotalTargets = 1,
                matrixCoveragePercent = matrixCoveragePercent,
                winnerCoveragePercent = winnerCoveragePercent,
            ),
        confidence =
            StrategyProbeAuditConfidence(
                level = confidenceLevel,
                score = 100,
                rationale = "Matrix coverage and winner strength are consistent",
            ),
    )

private fun validRecommendedProxyConfigJsonForCoordinator(): String =
    RipDpiProxyUIPreferences(
        protocols = RipDpiProtocolConfig(desyncUdp = true),
        chains =
            RipDpiChainConfig(
                tcpSteps =
                    listOf(
                        TcpChainStepModel(
                            kind = TcpChainStepKind.HostFake,
                            marker = "midhost+1",
                        ),
                    ),
            ),
        quic = RipDpiQuicConfig(fakeProfile = "realistic_initial"),
    ).toNativeConfigJson()

private fun strategyProbeFingerprint(
    ssid: String,
    gateway: String,
) = com.poyka.ripdpi.data.NetworkFingerprint(
    transport = "wifi",
    networkValidated = true,
    captivePortalDetected = false,
    privateDnsMode = "system",
    dnsServers = listOf("1.1.1.1"),
    wifi =
        com.poyka.ripdpi.data.WifiNetworkIdentityTuple(
            ssid = ssid,
            bssid = "aa:bb:cc:dd:ee:ff",
            gateway = gateway,
        ),
)
