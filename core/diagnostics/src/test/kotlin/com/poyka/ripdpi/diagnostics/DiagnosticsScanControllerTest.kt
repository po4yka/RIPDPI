package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.testing.FaultOutcome
import com.poyka.ripdpi.core.testing.FaultSpec
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

            val sessionId = services.scanController.startScan(ScanPathMode.IN_PATH).startedSessionId()
            advanceUntilIdle()
            val request =
                json.decodeFromString(
                    EngineScanRequestWire.serializer(),
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

            assertControllerSuspendFailsWith<IllegalArgumentException> {
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

            services.scanController.startScan(ScanPathMode.RAW_PATH).startedSessionId()

            assertControllerSuspendFailsWith<IllegalStateException> {
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

            services.scanController.startScan(ScanPathMode.RAW_PATH).startedSessionId()
            advanceUntilIdle()

            val failedSession = stores.sessionsState.value.single()
            assertEquals("failed", failedSession.status)
            assertEquals("boom", failedSession.summary)
            assertNull(services.timelineSource.activeScanProgress.value)
            assertEquals(1, bridgeFactory.bridge.destroyCount)
        }

    @Test
    fun `cancelled controller scope destroys bridge before execution starts`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json)
            val cancelledJob = Job().apply { cancel() }
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
                    controllerScope = CoroutineScope(cancelledJob),
                    json = json,
                )

            assertControllerSuspendFailsWith<CancellationException> {
                services.scanController.startScan(ScanPathMode.RAW_PATH)
            }

            assertEquals(1, bridgeFactory.bridge.destroyCount)
            assertFalse(services.scanController.hiddenAutomaticProbeActive.value)
            assertNull(services.timelineSource.activeScanProgress.value)
            val failedSession = stores.sessionsState.value.single()
            assertEquals("failed", failedSession.status)
            assertEquals("Diagnostics scan canceled during startup", failedSession.summary)
        }

    @Test
    fun `cancellation while bridge start is suspended clears startup state`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val startScanEntered = CompletableDeferred<Unit>()
            val releaseStartScan = CompletableDeferred<Unit>()
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.startScanEntered = startScanEntered
                    bridge.releaseStartScan = releaseStartScan
                    bridge.requireActiveContextOnDestroy = true
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

            val start = async { services.scanController.startScan(ScanPathMode.IN_PATH) }
            startScanEntered.await()
            val cancellation = CancellationException("cancelled while bridge start is suspended")
            start.cancel(cancellation)

            val thrown = assertControllerSuspendFailsWith<CancellationException> { start.await() }
            assertEquals(cancellation.message, thrown.message)
            assertEquals(1, bridgeFactory.bridge.destroyCount)
            assertFalse(services.scanController.hasActiveScan())
            assertFalse(services.scanController.hiddenAutomaticProbeActive.value)
            assertNull(services.timelineSource.activeScanProgress.value)
            val failedSession = stores.sessionsState.value.single()
            assertEquals("failed", failedSession.status)
            assertEquals("Diagnostics scan canceled during startup", failedSession.summary)
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

            services.scanController.startScan(ScanPathMode.RAW_PATH).startedSessionId()
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

            val sessionId = services.scanController.startScan(ScanPathMode.RAW_PATH).startedSessionId()
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

            val sessionId = services.scanController.startScan(ScanPathMode.RAW_PATH).startedSessionId()
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
    fun `manual start uses explicit selected profile over stored settings`() =
        runTest {
            val settings =
                defaultDiagnosticsAppSettings()
                    .toBuilder()
                    .setDiagnosticsActiveProfileId("default")
                    .build()
            val appSettingsRepository = FakeAppSettingsRepository(settings)
            val stores =
                FakeDiagnosticsHistoryStores().apply {
                    seedDefaultProfile(json)
                    seedStrategyProbeProfile(json)
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
                    scope = backgroundScope,
                    controllerScope = this,
                    json = json,
                )

            val sessionId =
                services.scanController
                    .startScan(
                        pathMode = ScanPathMode.RAW_PATH,
                        selectedProfileId = "automatic-probing",
                    ).startedSessionId()
            advanceUntilIdle()

            assertEquals("automatic-probing", stores.getScanSession(sessionId)?.profileId)
        }
}
