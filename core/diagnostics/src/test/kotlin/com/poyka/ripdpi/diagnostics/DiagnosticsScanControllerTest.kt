package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.testing.FaultOutcome
import com.poyka.ripdpi.core.testing.FaultSpec
import com.poyka.ripdpi.core.toRipDpiRuntimeContext
import com.poyka.ripdpi.data.PolicyHandoverEvent
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsScanControllerTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `in-path scan launch injects proxy settings into request`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json)
            val runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator()
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = runtimeCoordinator,
                    serviceStateStore = FakeServiceStateStore(),
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            val sessionId = services.scanController.startScan(ScanPathMode.IN_PATH)
            advanceUntilIdle()
            val request =
                json.decodeFromString(
                    ScanRequest.serializer(),
                    requireNotNull(bridgeFactory.bridge.startedRequestJson),
                )

            assertEquals(ScanPathMode.IN_PATH, request.pathMode)
            assertEquals("127.0.0.1", request.proxyHost)
            assertEquals(1080, request.proxyPort)
            assertEquals("completed", stores.getScanSession(sessionId)?.status)
            assertEquals(0, runtimeCoordinator.rawScanCount.get())
        }

    @Test
    fun `set active profile validates profile existence before updating settings`() =
        runTest {
            val appSettingsRepository = FakeAppSettingsRepository()
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = appSettingsRepository,
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json),
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            assertSuspendFailsWith<IllegalArgumentException> {
                services.scanController.setActiveProfile("missing-profile")
            }

            services.scanController.setActiveProfile("default")
            assertEquals("default", appSettingsRepository.snapshot().diagnosticsActiveProfileId)
        }

    @Test
    fun `duplicate active scan requests are rejected`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.autoCompleteOnStart = false
                }
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(),
                    scope = backgroundScope,
                    controllerScope = backgroundScope,
                    json = json,
                )

            services.scanController.startScan(ScanPathMode.RAW_PATH)

            assertSuspendFailsWith<IllegalStateException> {
                services.scanController.startScan(ScanPathMode.IN_PATH)
            }
            services.scanController.cancelActiveScan()
        }

    @Test
    fun `start failure marks session failed and clears active progress`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.faults.enqueue(
                        FaultSpec(
                            target = DiagnosticsBridgeFaultTarget.START_SCAN,
                            outcome = FaultOutcome.EXCEPTION,
                            message = "boom",
                        ),
                    )
                }
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
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

            assertSuspendFailsWith<java.io.IOException> {
                services.scanController.startScan(ScanPathMode.RAW_PATH)
            }

            val failedSession = stores.sessionsState.value.single()
            assertEquals("failed", failedSession.status)
            assertEquals("boom", failedSession.summary)
            assertNull(services.timelineSource.activeScanProgress.value)
            assertEquals(1, bridgeFactory.bridge.destroyCount)
        }

    @Test
    fun `cancel clears active progress and forwards cancel to the active bridge`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.autoCompleteOnStart = false
                }
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
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

            services.scanController.startScan(ScanPathMode.RAW_PATH)
            services.scanController.cancelActiveScan()

            val canceledSession = stores.sessionsState.value.single()
            assertNull(services.timelineSource.activeScanProgress.value)
            assertEquals("failed", canceledSession.status)
            assertEquals("Diagnostics scan canceled", canceledSession.summary)
            assertFalse(services.scanController.hasActiveScan())
            assertEquals(1, bridgeFactory.bridge.cancelCount)
            assertEquals(1, bridgeFactory.bridge.destroyCount)
        }

    @Test
    fun `poll failure during scan execution marks session failed and cleans up`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.autoCompleteOnStart = false
                    bridge.faults.enqueue(
                        FaultSpec(
                            target = DiagnosticsBridgeFaultTarget.POLL_PROGRESS,
                            outcome = FaultOutcome.EXCEPTION,
                            message = "poll crashed",
                        ),
                    )
                }
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
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

            val sessionId = services.scanController.startScan(ScanPathMode.RAW_PATH)
            advanceUntilIdle()

            val failedSession = stores.getScanSession(sessionId)
            assertEquals("failed", failedSession?.status)
            assertNull(services.timelineSource.activeScanProgress.value)
            assertEquals(1, bridgeFactory.bridge.destroyCount)
        }

    @Test
    fun `manual automatic probing does not persist remembered policy`() =
        runTest {
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setDiagnosticsActiveProfileId("automatic-probing")
                    .setNetworkStrategyMemoryEnabled(true)
                    .build()
            val appSettingsRepository = FakeAppSettingsRepository(settings)
            val stores = FakeDiagnosticsHistoryStores().apply { seedStrategyProbeProfile(json) }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.autoCompleteOnStart = false
                }
            val rememberedNetworkPolicyStore =
                DefaultRememberedNetworkPolicyStore(stores, TestDiagnosticsHistoryClock())
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
                    rememberedNetworkPolicyStore = rememberedNetworkPolicyStore,
                    networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            val sessionId = services.scanController.startScan(ScanPathMode.RAW_PATH)
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
            assertEquals(
                DiagnosticsScanLaunchOrigin.USER_INITIATED.storageValue,
                stores.getScanSession(sessionId)?.launchOrigin,
            )
            assertNull(stores.getScanSession(sessionId)?.triggerType)
        }

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
                    networkFingerprintProvider = FakeNetworkFingerprintProvider(),
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            val launched =
                services.scanController.launchAutomaticProbe(
                    settings = settings,
                    event =
                        PolicyHandoverEvent(
                            mode = com.poyka.ripdpi.data.Mode.VPN,
                            currentFingerprintHash = "network-a",
                            classification = "network_changed",
                            currentNetworkValidated = true,
                            currentCaptivePortalDetected = false,
                            usedRememberedPolicy = false,
                            policySignature = "baseline",
                            occurredAt = 10L,
                        ),
                )
            assertTrue(launched)
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
            assertEquals("network-a", launchedSession.triggerCurrentFingerprintHash)
        }
}

private suspend inline fun <reified T : Throwable> assertSuspendFailsWith(noinline block: suspend () -> Unit): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) {
            return error
        }
        throw error
    }
    fail("Expected ${T::class.java.simpleName} to be thrown")
    throw AssertionError("Unreachable")
}

private fun controllerStrategyProbeReport(
    sessionId: String,
    settings: com.poyka.ripdpi.proto.AppSettings,
) = ScanReport(
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
                    recommendedProxyConfigJson =
                        RipDpiProxyUIPreferences
                            .fromSettings(
                                settings,
                                null,
                                null,
                                settings.activeDnsSettings().toRipDpiRuntimeContext(),
                            ).toNativeConfigJson(),
                ),
        ),
)
