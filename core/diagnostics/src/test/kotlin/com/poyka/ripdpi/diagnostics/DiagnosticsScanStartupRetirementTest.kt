package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsScanStartupRetirementTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `startup retirement waits for confirmed registry detach before destroy`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val bridgeMutex = Mutex()
            val mutexEntered = CompletableDeferred<Unit>()
            val releaseMutex = CompletableDeferred<Unit>()
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
            val cancellation = CancellationException("caller canceled while registry was busy")
            lateinit var start: kotlinx.coroutines.Deferred<DiagnosticsManualScanStartResult>
            bridgeFactory.bridge.afterStartScan = {
                backgroundScope.launch {
                    bridgeMutex.withLock {
                        mutexEntered.complete(Unit)
                        releaseMutex.await()
                    }
                }
                mutexEntered.await()
                start.cancel(cancellation)
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
                    bridgeMutex = bridgeMutex,
                    json = json,
                )

            start = async(start = CoroutineStart.LAZY) { services.scanController.startScan(ScanPathMode.IN_PATH) }
            start.start()
            advanceTimeBy(2_500L)
            runCurrent()
            val destroyStartedBeforeDetach = destroyEntered.isCompleted
            val activeBeforeDetach = services.scanController.hasActiveScan()
            releaseMutex.complete(Unit)
            destroyEntered.await()
            releaseDestroy.complete(Unit)

            val thrown = assertControllerSuspendFailsWith<CancellationException> { start.await() }
            destroyCompleted.await()
            assertEquals(cancellation.message, thrown.message)
            assertFalse(destroyStartedBeforeDetach)
            assertTrue(activeBeforeDetach)
            assertEquals(1, bridgeFactory.bridge.destroyCount)
            assertFalse(services.scanController.hasActiveScan())
            assertNull(services.timelineSource.activeScanProgress.value)
        }

    @Test
    fun `startup retirement survives cancelled application io scope`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores().apply { seedDefaultProfile(json) }
            val applicationIoScope =
                CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val retirementQueue = BridgeRetirementQueue(StandardTestDispatcher(testScheduler))
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
            val cancellation = CancellationException("caller canceled with application scope")
            lateinit var start: kotlinx.coroutines.Deferred<DiagnosticsManualScanStartResult>
            bridgeFactory.bridge.afterStartScan = {
                applicationIoScope.cancel()
                start.cancel(cancellation)
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
                    scope = applicationIoScope,
                    controllerScope = backgroundScope,
                    retirementQueue = retirementQueue,
                    json = json,
                )

            start = async(start = CoroutineStart.LAZY) { services.scanController.startScan(ScanPathMode.IN_PATH) }
            start.start()
            advanceUntilIdle()
            val destroyScheduled = destroyEntered.isCompleted
            val drain = async { retirementQueue.closeAndDrain() }
            runCurrent()
            val drainWaitedForPendingDestroy = !drain.isCompleted
            releaseDestroy.complete(Unit)

            val thrown = assertControllerSuspendFailsWith<CancellationException> { start.await() }
            advanceUntilIdle()
            drain.await()
            val admissionAfterClose = retirementQueue.tryReserve(bridgeFactory.bridge)
            assertEquals(cancellation.message, thrown.message)
            assertTrue(destroyScheduled)
            assertTrue(drainWaitedForPendingDestroy)
            assertTrue(destroyCompleted.isCompleted)
            assertNull(admissionAfterClose)
            assertEquals(1, bridgeFactory.bridge.destroyCount)
            assertFalse(services.scanController.hasActiveScan())
            assertNull(services.timelineSource.activeScanProgress.value)
        }
}
