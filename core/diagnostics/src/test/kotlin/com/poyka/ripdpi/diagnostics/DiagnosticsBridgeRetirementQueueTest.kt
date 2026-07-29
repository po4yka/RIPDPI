package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
class DiagnosticsBridgeRetirementQueueTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `close racing reserved retirement cannot strand detached bridge`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val bridgeMutex = kotlinx.coroutines.sync.Mutex()
            val registry = ActiveScanRegistry(coordinatorTimelineSource(stores, backgroundScope), bridgeMutex)
            val queue = BridgeRetirementQueue(StandardTestDispatcher(testScheduler))
            val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json)
            val destroyEntered = CompletableDeferred<Unit>()
            val releaseDestroy = CompletableDeferred<Unit>()
            val destroyCompleted = CompletableDeferred<Unit>()
            bridgeFactory.bridge.destroyEntered = destroyEntered
            bridgeFactory.bridge.releaseDestroy = releaseDestroy
            bridgeFactory.bridge.destroyCompleted = destroyCompleted
            bridgeFactory.bridge.destroyIgnoresCancellation = true
            val service = BridgeExecutionService(bridgeFactory, registry, queue)
            val handle = service.createHandle("close-race", registerActiveBridge = true)
            bridgeMutex.lock()

            val retirement = async(start = CoroutineStart.UNDISPATCHED) { service.retireAfterStartupFailure(handle) }
            val close = async { queue.closeAndDrain(timeoutMs = 1_000L) }
            advanceTimeBy(1_001L)
            runCurrent()
            val closeResult = close.await()
            val activeWhileReserved = registry.hasVisibleActiveScan()
            bridgeMutex.unlock()
            destroyEntered.await()
            val activeAfterDetach = registry.hasVisibleActiveScan()
            releaseDestroy.complete(Unit)
            retirement.await()
            destroyCompleted.await()
            advanceUntilIdle()

            assertEquals(BridgeRetirementDrainResult.Pending(1), closeResult)
            assertTrue(activeWhileReserved)
            assertFalse(activeAfterDetach)
            assertEquals(BridgeRetirementDrainResult.Drained, queue.closeAndDrain(timeoutMs = 1_000L))
            assertTrue(queue.isSupervisorCompleted)
            assertEquals(1, bridgeFactory.bridge.destroyCount)
        }

    @Test
    fun `closed queue refuses retirement before registry detach`() =
        runTest {
            val stores = FakeDiagnosticsHistoryStores()
            val registry = ActiveScanRegistry(coordinatorTimelineSource(stores, backgroundScope))
            val queue = BridgeRetirementQueue(StandardTestDispatcher(testScheduler))
            val bridgeFactory = FakeNetworkDiagnosticsBridgeFactory(json)
            val service = BridgeExecutionService(bridgeFactory, registry, queue)
            val handle = service.createHandle("closed-queue", registerActiveBridge = true)
            assertEquals(BridgeRetirementDrainResult.Drained, queue.closeAndDrain(timeoutMs = 1_000L))

            assertControllerSuspendFailsWith<IllegalStateException> {
                service.retireAfterStartupFailure(handle)
            }
            val activeAfterRefusal = registry.hasVisibleActiveScan()
            val destroyCountAfterRefusal = bridgeFactory.bridge.destroyCount
            service.destroy(handle)

            assertTrue(activeAfterRefusal)
            assertEquals(0, destroyCountAfterRefusal)
            assertFalse(registry.hasVisibleActiveScan())
            assertEquals(1, bridgeFactory.bridge.destroyCount)
        }

    @Test
    fun `cancelled close leaves retirement owned until native destroy completes`() =
        runTest {
            val queue = BridgeRetirementQueue(StandardTestDispatcher(testScheduler))
            val bridge = FakeNetworkDiagnosticsBridge(json)
            val destroyEntered = CompletableDeferred<Unit>()
            val releaseDestroy = CompletableDeferred<Unit>()
            val destroyCompleted = CompletableDeferred<Unit>()
            bridge.destroyEntered = destroyEntered
            bridge.releaseDestroy = releaseDestroy
            bridge.destroyCompleted = destroyCompleted
            bridge.destroyIgnoresCancellation = true
            val reservation = requireNotNull(queue.tryReserve(bridge))
            queue.commit(reservation)
            destroyEntered.await()
            val close = async { queue.closeAndDrain(timeoutMs = 10_000L) }
            runCurrent()
            val cancellation = CancellationException("close caller canceled")
            close.cancel(cancellation)

            val thrown = assertControllerSuspendFailsWith<CancellationException> { close.await() }
            val admissionAfterCancellation = queue.tryReserve(FakeNetworkDiagnosticsBridge(json))
            val supervisorBeforeDestroy = queue.isSupervisorCompleted
            releaseDestroy.complete(Unit)
            destroyCompleted.await()
            advanceUntilIdle()

            assertEquals(cancellation.message, thrown.message)
            assertNull(admissionAfterCancellation)
            assertFalse(supervisorBeforeDestroy)
            assertEquals(BridgeRetirementDrainResult.Drained, queue.closeAndDrain(timeoutMs = 1_000L))
            assertTrue(queue.isSupervisorCompleted)
            assertEquals(1, bridge.destroyCount)
        }

    @Test
    fun `hanging destroy returns explicit pending result at drain timeout`() =
        runTest {
            val queue = BridgeRetirementQueue(StandardTestDispatcher(testScheduler))
            val bridge = FakeNetworkDiagnosticsBridge(json)
            val destroyEntered = CompletableDeferred<Unit>()
            val releaseDestroy = CompletableDeferred<Unit>()
            val destroyCompleted = CompletableDeferred<Unit>()
            bridge.destroyEntered = destroyEntered
            bridge.releaseDestroy = releaseDestroy
            bridge.destroyCompleted = destroyCompleted
            bridge.destroyIgnoresCancellation = true
            queue.commit(requireNotNull(queue.tryReserve(bridge)))
            destroyEntered.await()

            val close = async { queue.closeAndDrain(timeoutMs = 1_000L) }
            advanceTimeBy(1_001L)
            runCurrent()
            val timedOut = close.await()
            val supervisorAtTimeout = queue.isSupervisorCompleted
            releaseDestroy.complete(Unit)
            destroyCompleted.await()
            advanceUntilIdle()

            assertEquals(BridgeRetirementDrainResult.Pending(1), timedOut)
            assertFalse(supervisorAtTimeout)
            assertEquals(BridgeRetirementDrainResult.Drained, queue.closeAndDrain(timeoutMs = 1_000L))
            assertTrue(queue.isSupervisorCompleted)
            assertEquals(1, bridge.destroyCount)
        }
}
