package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RememberedNetworkPolicySource
import com.poyka.ripdpi.data.diagnostics.DefaultNetworkDnsPathPreferenceStore
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.data.diagnostics.decodedSource
import com.poyka.ripdpi.diagnostics.application.DiagnosticsScanOrigin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsScanManualStrategyProbeCoordinatorTest {
    private val json = diagnosticsTestJson()

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

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = ::runSettledRawPathBlock)

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

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = ::runSettledRawPathBlock)

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

            fixtures.coordinator.execute(prepared, handle, rawPathRunner = ::runSettledRawPathBlock)

            assertEquals(1, stores.rememberedPoliciesState.value.size)
            assertEquals(
                preparedFingerprint.scopeKey(),
                stores.rememberedPoliciesState.value
                    .single()
                    .fingerprintHash,
            )
            assertEquals(
                RememberedNetworkPolicySource.STRATEGY_PROBE_MANUAL,
                stores.rememberedPoliciesState.value
                    .single()
                    .decodedSource(),
            )
        }
}
