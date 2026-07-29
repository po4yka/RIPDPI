package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.PolicyHandoverEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsScanControllerHiddenProbeTest {
    private val json = diagnosticsTestJson()
    private val automaticProbeFingerprintProvider = FakeNetworkFingerprintProvider()

    @Test
    fun `manual start during hidden automatic probe returns conflict result`() =
        runTest {
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setDiagnosticsActiveProfileId("automatic-audit")
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val appSettingsRepository = FakeAppSettingsRepository(settings)
            val stores =
                FakeDiagnosticsHistoryStores().apply {
                    seedStrategyProbeProfile(json)
                    addAutomaticAuditProfile(json)
                }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.autoCompleteOnStart = false
                }
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = appSettingsRepository,
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                    networkFingerprintProvider = automaticProbeFingerprintProvider,
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            assertFalse(
                services.scanController.launchAutomaticProbe(
                    settings = settings,
                    event =
                        PolicyHandoverEvent(
                            deliveryId = "delivery-hidden-conflict",
                            mode = com.poyka.ripdpi.data.Mode.VPN,
                            currentFingerprintHash = automaticProbeFingerprintProvider.capture().scopeKey(),
                            classification = "transport_switch",
                            currentNetworkValidated = true,
                            currentCaptivePortalDetected = false,
                            usedRememberedPolicy = false,
                            occurredAt = 10L,
                        ),
                ),
            )

            val result = services.scanController.startScan(ScanPathMode.RAW_PATH)

            assertTrue(result is DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution)
            val conflict = result as DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution
            assertEquals("Automatic audit", conflict.profileName)
            assertEquals(ScanPathMode.RAW_PATH, conflict.pathMode)
            assertEquals(ScanKind.STRATEGY_PROBE, conflict.scanKind)
            assertTrue(conflict.isFullAudit)
            assertTrue(services.scanController.hiddenAutomaticProbeActive.value)
        }

    @Test
    fun `wait resolution starts queued manual request from original snapshot`() =
        runTest {
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setDiagnosticsActiveProfileId("automatic-audit")
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val appSettingsRepository = FakeAppSettingsRepository(settings)
            val stores =
                FakeDiagnosticsHistoryStores().apply {
                    seedStrategyProbeProfile(json)
                    addAutomaticAuditProfile(json)
                }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.autoCompleteOnStart = false
                }
            val services = createServicesWithHiddenProbeCapable(appSettingsRepository, stores, bridgeFactory)

            assertFalse(
                services.scanController.launchAutomaticProbe(
                    settings = settings,
                    event = automaticProbeFingerprintProvider.transportSwitchHandoverEvent(),
                ),
            )
            val conflict =
                services.scanController.startScan(ScanPathMode.RAW_PATH)
                    as DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution

            appSettingsRepository.update { diagnosticsActiveProfileId = "default" }

            val hiddenSessionId =
                stores.sessionsState.value
                    .single()
                    .id
            completeHiddenScan(bridgeFactory, hiddenSessionId, settings)
            advanceUntilIdle()

            val resolution =
                services.scanController.resolveHiddenProbeConflict(
                    requestId = conflict.requestId,
                    action = HiddenProbeConflictAction.WAIT,
                )

            val sessionId = resolution.startedSessionId()
            assertEquals("automatic-audit", stores.getScanSession(sessionId)?.profileId)
            assertFalse(services.scanController.hiddenAutomaticProbeActive.value)
        }

    private fun kotlinx.coroutines.test.TestScope.createServicesWithHiddenProbeCapable(
        appSettingsRepository: FakeAppSettingsRepository,
        stores: FakeDiagnosticsHistoryStores,
        bridgeFactory: FakeNetworkDiagnosticsBridgeFactory,
    ) = createDiagnosticsServices(
        context = TestContext(),
        appSettingsRepository = appSettingsRepository,
        stores = stores,
        networkMetadataProvider = FakeNetworkMetadataProvider(),
        diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
        networkDiagnosticsBridgeFactory = bridgeFactory,
        runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
        serviceStateStore = FakeServiceStateStore(),
        networkFingerprintProvider = automaticProbeFingerprintProvider,
        scope = backgroundScope,
        controllerScope = this,
        json = json,
    )

    @Test
    fun `cancel and run cancels hidden probe with dedicated summary`() =
        runTest {
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setDiagnosticsActiveProfileId("automatic-audit")
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val stores =
                FakeDiagnosticsHistoryStores().apply {
                    seedStrategyProbeProfile(json)
                    addAutomaticAuditProfile(json)
                }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.autoCompleteOnStart = false
                }
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(settings),
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                    networkFingerprintProvider = automaticProbeFingerprintProvider,
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            assertFalse(
                services.scanController.launchAutomaticProbe(
                    settings = settings,
                    event =
                        PolicyHandoverEvent(
                            deliveryId = "delivery-stop-and-start",
                            mode = com.poyka.ripdpi.data.Mode.VPN,
                            currentFingerprintHash = automaticProbeFingerprintProvider.capture().scopeKey(),
                            classification = "transport_switch",
                            currentNetworkValidated = true,
                            currentCaptivePortalDetected = false,
                            usedRememberedPolicy = false,
                            occurredAt = 10L,
                        ),
                ),
            )
            val conflict =
                services.scanController.startScan(ScanPathMode.RAW_PATH)
                    as DiagnosticsManualScanStartResult.RequiresHiddenProbeResolution

            val resolution =
                services.scanController.resolveHiddenProbeConflict(
                    requestId = conflict.requestId,
                    action = HiddenProbeConflictAction.CANCEL_AND_RUN,
                )
            val manualSessionId = resolution.startedSessionId()
            advanceUntilIdle()

            val hiddenSession =
                stores.sessionsState.value.first { it.id != manualSessionId }
            assertEquals("failed", hiddenSession.status)
            assertEquals(
                BackgroundAutomaticProbeCanceledToStartManualDiagnosticsSummary,
                hiddenSession.summary,
            )
            assertEquals(1, bridgeFactory.bridge.cancelCount)
        }
}
