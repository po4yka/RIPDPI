package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.diagnostics.DefaultNetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.decodedSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

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

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

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

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

            assertTrue(stores.rememberedPoliciesState.value.isEmpty())
        }

    @Test
    fun `manual automatic probing does not remember validated network policy`() =
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
            val timelineSource = coordinatorTimelineSource(stores, backgroundScope)
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
            val timelineSource = coordinatorTimelineSource(stores, backgroundScope)
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

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = { block -> block() })

            val rememberedPolicy = stores.rememberedPoliciesState.value.single()
            assertEquals(preparedFingerprint.scopeKey(), rememberedPolicy.fingerprintHash)
            assertNotEquals(changedFingerprint.scopeKey(), rememberedPolicy.fingerprintHash)
        }
}
