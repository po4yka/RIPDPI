package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.testing.FaultOutcome
import com.poyka.ripdpi.core.testing.FaultSpec
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.diagnostics.DefaultRememberedNetworkPolicyStore
import com.poyka.ripdpi.diagnostics.contract.engine.EngineScanRequestWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.Proxy),
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
            val startFailureCanary = "start-secret-token=canary.example"
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.faults.enqueue(
                        FaultSpec(
                            target = DiagnosticsBridgeFaultTarget.START_SCAN,
                            outcome = FaultOutcome.EXCEPTION,
                            message = startFailureCanary,
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
            assertEquals("Diagnostics scan failed to start", failedSession.summary)
            assertFalse(failedSession.summary.contains(startFailureCanary))
            assertFalse(
                services.shareService
                    .buildShareSummary(failedSession.id)
                    .body
                    .contains(startFailureCanary),
            )
            assertNull(services.timelineSource.activeScanProgress.value)
            assertEquals(1, bridgeFactory.bridge.destroyCount)
        }

    @Test
    fun `cancelled application scope rejects startup before durable resources`() =
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

            assertEquals(0, bridgeFactory.bridge.destroyCount)
            assertFalse(services.scanController.hiddenAutomaticProbeActive.value)
            assertNull(services.timelineSource.activeScanProgress.value)
            assertTrue(stores.sessionsState.value.isEmpty())
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
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.Proxy),
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
    fun `caller cancellation at every persistence boundary terminalizes startup`() =
        runTest {
            StartupPersistenceBoundary.entries.forEach { boundary ->
                val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
                val boundaryEntered = CompletableDeferred<Unit>()
                stores.suspendStartupAt(boundary, boundaryEntered)
                val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json)
                val services =
                    createDiagnosticsServices(
                        context = TestContext(),
                        appSettingsRepository = FakeAppSettingsRepository(),
                        stores = stores,
                        networkMetadataProvider = FakeNetworkMetadataProvider(),
                        diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                        networkDiagnosticsBridgeFactory = bridgeFactory,
                        runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                        serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.Proxy),
                        scope = backgroundScope,
                        controllerScope = backgroundScope,
                        json = json,
                    )

                val start = async { services.scanController.startScan(ScanPathMode.IN_PATH) }
                boundaryEntered.await()
                val cancellation = CancellationException("caller canceled at ${boundary.name}")
                start.cancel(cancellation)

                val thrown = assertControllerSuspendFailsWith<CancellationException> { start.await() }
                assertEquals(cancellation.message, thrown.message)
                assertEquals(
                    "failed",
                    stores.sessionsState.value
                        .single()
                        .status,
                )
                assertEquals(
                    "Diagnostics scan canceled during startup",
                    stores.sessionsState.value
                        .single()
                        .summary,
                )
                assertFalse(services.scanController.hasActiveScan())
                assertNull(services.timelineSource.activeScanProgress.value)
                assertEquals(0, bridgeFactory.bridge.destroyCount)
            }
        }

    @Test
    fun `caller cancellation after bridge start cleans registered bridge before launch`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val destroyEntered = CompletableDeferred<Unit>()
            val releaseDestroy = CompletableDeferred<Unit>()
            val destroyCompleted = CompletableDeferred<Unit>()
            val bridgeFactory =
                FakeNetworkDiagnosticsBridgeFactory(json).apply {
                    bridge.destroyEntered = destroyEntered
                    bridge.releaseDestroy = releaseDestroy
                    bridge.destroyCompleted = destroyCompleted
                    bridge.destroyIgnoresCancellation = true
                }
            val cancellation = CancellationException("caller canceled after bridge start")
            lateinit var start: kotlinx.coroutines.Deferred<DiagnosticsManualScanStartResult>
            bridgeFactory.bridge.afterStartScan = { start.cancel(cancellation) }
            val services =
                createDiagnosticsServices(
                    context = TestContext(),
                    appSettingsRepository = FakeAppSettingsRepository(),
                    stores = stores,
                    networkMetadataProvider = FakeNetworkMetadataProvider(),
                    diagnosticsContextProvider = FakeDiagnosticsContextProvider(),
                    networkDiagnosticsBridgeFactory = bridgeFactory,
                    runtimeCoordinator = FakeDiagnosticsRuntimeCoordinator(),
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.Proxy),
                    scope = backgroundScope,
                    controllerScope = backgroundScope,
                    json = json,
                )

            start = async(start = CoroutineStart.LAZY) { services.scanController.startScan(ScanPathMode.IN_PATH) }
            start.start()
            destroyEntered.await()
            val startupCompletedBeforeNativeDestroy = start.isCompleted
            val activeBeforeNativeDestroy = services.scanController.hasActiveScan()
            val progressBeforeNativeDestroy = services.timelineSource.activeScanProgress.value
            val sessionStatusBeforeNativeDestroy =
                stores.sessionsState.value
                    .single()
                    .status
            releaseDestroy.complete(Unit)

            val thrown = assertControllerSuspendFailsWith<CancellationException> { start.await() }
            destroyCompleted.await()
            assertEquals(cancellation.message, thrown.message)
            assertTrue(startupCompletedBeforeNativeDestroy)
            assertFalse(activeBeforeNativeDestroy)
            assertNull(progressBeforeNativeDestroy)
            assertEquals("failed", sessionStatusBeforeNativeDestroy)
            assertEquals(1, bridgeFactory.bridge.destroyCount)
            assertEquals(
                "failed",
                stores.sessionsState.value
                    .single()
                    .status,
            )
            assertFalse(services.scanController.hasActiveScan())
            assertNull(services.timelineSource.activeScanProgress.value)
        }

    @Test
    fun `caller cancellation during bridge registration does not leak created bridge`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val bridge = FakeNetworkDiagnosticsBridge(json)
            val destroyEntered = CompletableDeferred<Unit>()
            val releaseDestroy = CompletableDeferred<Unit>()
            val destroyCompleted = CompletableDeferred<Unit>()
            bridge.destroyEntered = destroyEntered
            bridge.releaseDestroy = releaseDestroy
            bridge.destroyCompleted = destroyCompleted
            bridge.destroyIgnoresCancellation = true
            val cancellation = CancellationException("caller canceled during bridge registration")
            lateinit var start: kotlinx.coroutines.Deferred<DiagnosticsManualScanStartResult>
            val bridgeFactory =
                object : com.poyka.ripdpi.core.NetworkDiagnosticsBridgeFactory {
                    override fun create(): com.poyka.ripdpi.core.NetworkDiagnosticsBridge {
                        start.cancel(cancellation)
                        return bridge
                    }
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
                    serviceStateStore = FakeServiceStateStore(AppStatus.Running to Mode.Proxy),
                    scope = backgroundScope,
                    controllerScope = backgroundScope,
                    json = json,
                )

            start = async(start = CoroutineStart.LAZY) { services.scanController.startScan(ScanPathMode.IN_PATH) }
            start.start()
            destroyEntered.await()
            val startupCompletedBeforeNativeDestroy = start.isCompleted
            val activeBeforeNativeDestroy = services.scanController.hasActiveScan()
            val sessionStatusBeforeNativeDestroy =
                stores.sessionsState.value
                    .single()
                    .status
            releaseDestroy.complete(Unit)

            val thrown = assertControllerSuspendFailsWith<CancellationException> { start.await() }
            destroyCompleted.await()
            assertEquals(cancellation.message, thrown.message)
            assertTrue(startupCompletedBeforeNativeDestroy)
            assertFalse(activeBeforeNativeDestroy)
            assertEquals("failed", sessionStatusBeforeNativeDestroy)
            assertEquals(1, bridge.destroyCount)
            assertEquals(
                "failed",
                stores.sessionsState.value
                    .single()
                    .status,
            )
            assertFalse(services.scanController.hasActiveScan())
            assertNull(services.timelineSource.activeScanProgress.value)
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

private enum class StartupPersistenceBoundary {
    SESSION,
    SNAPSHOT,
    CONTEXT,
}

private fun FakeDiagnosticsHistoryStores.suspendStartupAt(
    boundary: StartupPersistenceBoundary,
    entered: CompletableDeferred<Unit>,
) {
    when (boundary) {
        StartupPersistenceBoundary.SESSION -> {
            afterUpsertScanSession = { session ->
                if (session.status == "running") {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        }

        StartupPersistenceBoundary.SNAPSHOT -> {
            afterUpsertSnapshot = {
                entered.complete(Unit)
                awaitCancellation()
            }
        }

        StartupPersistenceBoundary.CONTEXT -> {
            afterUpsertContextSnapshot = {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
    }
}
