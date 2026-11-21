package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.DefaultNetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanOrigin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsScanStrategyProbeCoordinatorTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `background automatic probing remembers validated network policy`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = coordinatorTimelineSource(stores, backgroundScope)
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

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = ::runSettledRawPathBlock)

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
    fun `fresh eligible result that will not be persisted keeps the remembered combination`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = coordinatorTimelineSource(stores, backgroundScope)
            val serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.VPN)
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

            suspend fun runBackgroundProbe(
                sessionId: String,
                settings: com.poyka.ripdpi.proto.AppSettings,
                report: ScanReport,
            ) {
                val prepared =
                    preparedDiagnosticsScan(
                        sessionId = sessionId,
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
                                sessionId = sessionId,
                                phase = "complete",
                                completedSteps = 1,
                                totalSteps = 1,
                                message = "complete",
                                isFinished = true,
                            ),
                        )
                        enqueueReport(report)
                    }
                fixtures.activeScanRegistry.registerBridge(bridge, sessionId, prepared.registerActiveBridge)
                fixtures.coordinator.execute(
                    prepared,
                    BridgeSessionHandle(bridge, sessionId, prepared.registerActiveBridge),
                    rawPathRunner = ::runSettledRawPathBlock,
                )
            }

            val rememberingSettings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            runBackgroundProbe(
                "session-remembered",
                rememberingSettings,
                scanReportWithStrategyProbe("session-remembered", rememberingSettings),
            )
            val rememberedBefore = stores.rememberedPoliciesState.value.single()

            // Strategy memory is off, so this eligible result cannot be persisted and must not win.
            val forgetfulSettings = rememberingSettings.toBuilder().setNetworkStrategyMemoryEnabled(false).build()
            runBackgroundProbe(
                "session-fresh",
                forgetfulSettings,
                scanReportWithStrategyProbe(
                    "session-fresh",
                    forgetfulSettings,
                    resolverRecommendation = resolverRecommendationForCoordinator(),
                ).copy(results = scanReportWithResolverRecommendation("session-fresh").results),
            )

            assertEquals(listOf(rememberedBefore), stores.rememberedPoliciesState.value)
            assertNull(stores.getNetworkDnsPathPreference(preparedFingerprint.scopeKey()))
        }

    @Test
    fun `background automatic probing skips remembered policy when audit assessment is missing`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = coordinatorTimelineSource(stores, backgroundScope)
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

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = ::runSettledRawPathBlock)

            assertTrue(stores.rememberedPoliciesState.value.isEmpty())
        }

    @Test
    fun `background automatic probing skips remembered policy when confidence is medium`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = coordinatorTimelineSource(stores, backgroundScope)
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

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = ::runSettledRawPathBlock)

            assertTrue(stores.rememberedPoliciesState.value.isEmpty())
        }

    @Test
    fun `background automatic probing skips remembered policy when coverage is insufficient`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = coordinatorTimelineSource(stores, backgroundScope)
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

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = ::runSettledRawPathBlock)

            assertTrue(stores.rememberedPoliciesState.value.isEmpty())
        }

    @Test
    fun `background automatic probing preserves report without recommendation when network changes`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val clock = TestDiagnosticsHistoryClock()
            val timelineSource = coordinatorTimelineSource(stores, backgroundScope)
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

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = ::runSettledRawPathBlock)

            assertTrue(stores.rememberedPoliciesState.value.isEmpty())
            val session = requireNotNull(stores.getScanSession(prepared.sessionId))
            val persistedReport =
                json.decodeEngineScanReportWire(requireNotNull(session.reportJson)).toScanReport()
            assertEquals("completed", session.status)
            assertEquals("success", persistedReport.results.single().outcome)
            assertNull(requireNotNull(persistedReport.strategyProbeReport).recommendation)
        }
}
