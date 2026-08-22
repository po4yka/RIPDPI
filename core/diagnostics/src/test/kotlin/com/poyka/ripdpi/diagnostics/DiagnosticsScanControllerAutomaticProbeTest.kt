package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsScanControllerAutomaticProbeTest {
    private val json = diagnosticsTestJson()
    private val automaticProbeFingerprintProvider = FakeNetworkFingerprintProvider()

    @Test
    fun `automatic handover replay reuses its durable scan session`() =
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
            val event = automaticProbeFingerprintProvider.transportSwitchHandoverEvent()

            val firstLaunch = services.scanController.launchAutomaticProbe(settings, event)
            assertEquals(AutomaticProbeLaunchOutcome.LAUNCHED, firstLaunch)
            val retryWhileRunning = services.scanController.launchAutomaticProbe(settings, event)
            assertEquals(AutomaticProbeLaunchOutcome.RETRY, retryWhileRunning)

            assertEquals(1, stores.sessionsState.value.size)

            val sessionId =
                stores.sessionsState.value
                    .single()
                    .id
            completeHiddenScan(bridgeFactory, sessionId, settings)
            advanceUntilIdle()

            val replaySettled = services.scanController.launchAutomaticProbe(settings, event)
            assertEquals(AutomaticProbeLaunchOutcome.SETTLED, replaySettled)
            assertEquals(1, stores.sessionsState.value.size)
        }

    @Test
    fun `automatic handover startup failure terminalizes its durable session at every boundary`() =
        runTest {
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            AutomaticStartupFailurePoint.entries.forEach { failurePoint ->
                assertAutomaticStartupFailureIsTerminal(settings, failurePoint)
            }
        }

    private suspend fun TestScope.assertAutomaticStartupFailureIsTerminal(
        settings: com.poyka.ripdpi.proto.AppSettings,
        failurePoint: AutomaticStartupFailurePoint,
    ) {
        val stores = FakeDiagnosticsHistoryStores().apply { seedStrategyProbeProfile(json) }
        val event =
            automaticProbeFingerprintProvider
                .transportSwitchHandoverEvent()
                .copy(deliveryId = "delivery-${failurePoint.name}")
        val firstBridgeFactory = startupFailureBridgeFactory(failurePoint)
        stores.injectStartupFailure(failurePoint)
        val firstServices = automaticProbeServices(settings, stores, firstBridgeFactory)

        assertControllerSuspendFailsWith<IllegalStateException> {
            firstServices.scanController.launchAutomaticProbe(settings, event)
        }
        assertEquals(
            "failed",
            stores.sessionsState.value
                .single()
                .status,
        )
        assertFalse(firstServices.scanController.hasActiveScan())
        assertFalse(firstServices.scanController.hiddenAutomaticProbeActive.value)

        stores.clearStartupFailures()
        val replayBridgeFactory =
            FakeNetworkDiagnosticsBridgeFactory(json).apply {
                bridge.autoCompleteOnStart = false
            }
        val replayServices = automaticProbeServices(settings, stores, replayBridgeFactory)

        val replaySettled = replayServices.scanController.launchAutomaticProbe(settings, event)
        assertEquals(AutomaticProbeLaunchOutcome.SETTLED, replaySettled)
        assertEquals(1, stores.sessionsState.value.size)
        assertFalse(replayServices.scanController.hiddenAutomaticProbeActive.value)
    }

    private fun startupFailureBridgeFactory(
        failurePoint: AutomaticStartupFailurePoint,
    ): com.poyka.ripdpi.core.NetworkDiagnosticsBridgeFactory =
        if (failurePoint == AutomaticStartupFailurePoint.BEFORE_EXECUTION_REGISTRATION) {
            object : com.poyka.ripdpi.core.NetworkDiagnosticsBridgeFactory {
                override fun create() = error("injected startup failure")
            }
        } else {
            FakeNetworkDiagnosticsBridgeFactory(json)
        }

    private fun FakeDiagnosticsHistoryStores.injectStartupFailure(failurePoint: AutomaticStartupFailurePoint) {
        when (failurePoint) {
            AutomaticStartupFailurePoint.AFTER_SESSION -> {
                afterUpsertScanSession = { session ->
                    if (session.status == "running") error("injected startup failure")
                }
            }

            AutomaticStartupFailurePoint.AFTER_SNAPSHOT -> {
                afterUpsertSnapshot = { error("injected startup failure") }
            }

            AutomaticStartupFailurePoint.AFTER_CONTEXT -> {
                afterUpsertContextSnapshot = { error("injected startup failure") }
            }

            AutomaticStartupFailurePoint.BEFORE_EXECUTION_REGISTRATION -> {
            }
        }
    }

    private fun FakeDiagnosticsHistoryStores.clearStartupFailures() {
        afterUpsertScanSession = {}
        afterUpsertSnapshot = {}
        afterUpsertContextSnapshot = {}
    }

    private fun TestScope.automaticProbeServices(
        settings: com.poyka.ripdpi.proto.AppSettings,
        stores: FakeDiagnosticsHistoryStores,
        bridgeFactory: com.poyka.ripdpi.core.NetworkDiagnosticsBridgeFactory,
    ): DiagnosticsServicesBundle =
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
            controllerScope = backgroundScope,
            json = json,
        )

    @Test
    fun `scheduled automatic probing persists launch metadata`() =
        runTest {
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val stores = FakeDiagnosticsHistoryStores().apply { seedStrategyProbeProfile(json) }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.autoCompleteOnStart = false
                }
            val runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator()
            val networkFingerprintProvider = FakeNetworkFingerprintProvider()
            val rememberedNetworkPolicyStore =
                DefaultRememberedNetworkPolicyStore(stores, TestDiagnosticsHistoryClock())
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(settings),
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = runtimeCoordinator,
                    serviceStateStore = FakeServiceStateStore(),
                    rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
                    networkFingerprintProvider = networkFingerprintProvider,
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            val outcome =
                services.scanController.launchAutomaticProbe(
                    settings = settings,
                    event =
                        PolicyHandoverEvent(
                            deliveryId = "delivery-network-changed",
                            mode = com.poyka.ripdpi.data.Mode.VPN,
                            currentFingerprintHash = networkFingerprintProvider.capture().scopeKey(),
                            classification = "network_changed",
                            currentNetworkValidated = true,
                            currentCaptivePortalDetected = false,
                            usedRememberedPolicy = false,
                            occurredAt = 10L,
                        ),
                )
            assertEquals(AutomaticProbeLaunchOutcome.LAUNCHED, outcome)
            val launchedSession = stores.sessionsState.value.single()
            val sessionId = launchedSession.id
            bridgeFactory.bridge.enqueueProgress(
                ScanProgress(
                    sessionId = sessionId,
                    phase = "complete",
                    completedSteps = 1,
                    totalSteps = 1,
                    message = "complete",
                    isFinished = true,
                ),
            )
            bridgeFactory.bridge.enqueueReport(
                controllerStrategyProbeReport(sessionId = sessionId, settings = settings),
            )
            advanceUntilIdle()

            assertTrue(stores.rememberedPoliciesState.value.isEmpty())
            assertEquals(1, runtimeCoordinator.automaticRawScanCount.get())
            assertEquals(0, runtimeCoordinator.rawScanCount.get())
            assertEquals(DiagnosticsScanLaunchOrigin.AUTOMATIC_BACKGROUND.storageValue, launchedSession.launchOrigin)
            assertEquals(DiagnosticsScanTriggerType.POLICY_HANDOVER.storageValue, launchedSession.triggerType)
            assertEquals("network_changed", launchedSession.triggerClassification)
            assertEquals(10L, launchedSession.triggerOccurredAt)
            assertNull(launchedSession.triggerPreviousFingerprintHash)
            assertEquals(networkFingerprintProvider.capture().scopeKey(), launchedSession.triggerCurrentFingerprintHash)
        }

    @Test
    fun `automatic handover replay acknowledges a stale network without launching`() =
        runTest {
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val stores = FakeDiagnosticsHistoryStores().apply { seedStrategyProbeProfile(json) }
            val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json)
            val networkFingerprintProvider = FakeNetworkFingerprintProvider()
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
                    networkFingerprintProvider = networkFingerprintProvider,
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            val settled =
                services.scanController.launchAutomaticProbe(
                    settings = settings,
                    event =
                        automaticProbeFingerprintProvider
                            .transportSwitchHandoverEvent()
                            .copy(currentFingerprintHash = "stale-network"),
                )

            assertEquals(AutomaticProbeLaunchOutcome.SETTLED, settled)
            assertTrue(stores.sessionsState.value.isEmpty())
            assertNull(bridgeFactory.bridge.startedRequestJson)
        }

    @Test
    fun `automatic handover replay retains delivery when fingerprint capture is unavailable`() =
        runTest {
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val stores = FakeDiagnosticsHistoryStores().apply { seedStrategyProbeProfile(json) }
            val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json)
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
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            val settled =
                services.scanController.launchAutomaticProbe(
                    settings,
                    automaticProbeFingerprintProvider.transportSwitchHandoverEvent(),
                )

            assertEquals(AutomaticProbeLaunchOutcome.RETRY, settled)
            assertTrue(stores.sessionsState.value.isEmpty())
            assertNull(bridgeFactory.bridge.startedRequestJson)
        }

    @Test
    fun `automatic handover replay acknowledges an event from another service mode`() =
        runTest {
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val stores = FakeDiagnosticsHistoryStores().apply { seedStrategyProbeProfile(json) }
            val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json)
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(settings),
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(activeMode = "Proxy"),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy),
                    networkFingerprintProvider = automaticProbeFingerprintProvider,
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            val settled =
                services.scanController.launchAutomaticProbe(
                    settings,
                    automaticProbeFingerprintProvider.transportSwitchHandoverEvent(),
                )

            assertEquals(AutomaticProbeLaunchOutcome.SETTLED, settled)
            assertTrue(stores.sessionsState.value.isEmpty())
            assertNull(bridgeFactory.bridge.startedRequestJson)
        }
}

private enum class AutomaticStartupFailurePoint {
    AFTER_SESSION,
    AFTER_SNAPSHOT,
    AFTER_CONTEXT,
    BEFORE_EXECUTION_REGISTRATION,
}
