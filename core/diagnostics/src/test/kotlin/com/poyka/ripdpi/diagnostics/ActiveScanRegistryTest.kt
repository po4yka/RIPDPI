package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.testing.FaultOutcome
import com.poyka.ripdpi.core.testing.FaultSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveScanRegistryTest {
    private val json = diagnosticsTestJson()

    @Test
    fun `cancelScan clears execution and bridge when native cancellation fails`() =
        runTest {
            val registry =
                ActiveScanRegistry(coordinatorTimelineSource(FakeDiagnosticsHistoryStores(), backgroundScope))
            val bridge =
                FakeNetworkDiagnosticsBridge(json).apply {
                    faults.enqueue(
                        FaultSpec(
                            target = DiagnosticsBridgeFaultTarget.CANCEL,
                            outcome = FaultOutcome.EXCEPTION,
                            message = "cancel failed",
                        ),
                    )
                }
            val executionJob = backgroundScope.launch { awaitCancellation() }
            registry.registerBridge(bridge, "session-one", registerActiveBridge = true)
            registry.registerExecution("session-one", executionJob, registerActiveBridge = true)

            val cancellation = registry.cancelScan("session-one")

            assertTrue(cancellation?.failure is IOException)
            assertTrue(executionJob.isCancelled)
            assertEquals(1, bridge.destroyCount)
            assertFalse(registry.hasVisibleActiveScan())
        }

    @Test
    fun `clearing one parallel session keeps progress owned by another`() =
        runTest {
            val timeline = coordinatorTimelineSource(FakeDiagnosticsHistoryStores(), backgroundScope)
            val registry = ActiveScanRegistry(timeline)
            val firstBridge = FakeNetworkDiagnosticsBridge(json)
            val secondBridge = FakeNetworkDiagnosticsBridge(json)
            registry.registerBridge(firstBridge, "session-one", registerActiveBridge = true)
            registry.registerBridge(secondBridge, "session-two", registerActiveBridge = true)
            val firstProgress = ScanProgress("session-one", "running", 1, 3, "first")
            val secondProgress = ScanProgress("session-two", "running", 2, 3, "second")

            registry.updateProgress("session-one", firstProgress)
            registry.updateProgress("session-two", secondProgress)
            registry.updateProgress("session-one", null)

            assertEquals("session-two", timeline.activeScanProgress.value?.sessionId)

            registry.updateProgress("session-one", firstProgress)
            registry.clearBridge(secondBridge, "session-two", registerActiveBridge = true)

            assertEquals("session-one", timeline.activeScanProgress.value?.sessionId)
        }

    @Test
    fun `run-owned session rejects execution registration after startup cancellation`() =
        runTest {
            val registry =
                ActiveScanRegistry(coordinatorTimelineSource(FakeDiagnosticsHistoryStores(), backgroundScope))
            val bridge = FakeNetworkDiagnosticsBridge(json)
            registry.registerBridge(bridge, "starting-session", registerActiveBridge = true)

            val cancellation = registry.cancelScan("starting-session")
            val lateExecution = Job()

            assertEquals("starting-session", cancellation?.sessionId)
            assertFalse(registry.registerExecution("starting-session", lateExecution, registerActiveBridge = true))
            assertEquals(1, bridge.destroyCount)
            assertFalse(registry.hasVisibleActiveScan())

            lateExecution.cancel()
            registry.removePreparedScan("starting-session")
        }

    @Test
    fun `cancelling hidden execution remains registered until cleanup completes`() =
        runTest {
            val registry =
                ActiveScanRegistry(coordinatorTimelineSource(FakeDiagnosticsHistoryStores(), backgroundScope))
            val bridge = FakeNetworkDiagnosticsBridge(json)
            val releaseCleanup = CompletableDeferred<Unit>()
            val executionJob =
                backgroundScope.launch {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { releaseCleanup.await() }
                    }
                }
            registry.registerBridge(bridge, "hidden-session", registerActiveBridge = false)
            registry.registerExecution("hidden-session", executionJob, registerActiveBridge = false)
            runCurrent()

            executionJob.cancel()
            runCurrent()

            assertFalse(executionJob.isCompleted)
            assertTrue(registry.hasRegisteredExecution("hidden-session"))

            releaseCleanup.complete(Unit)
            executionJob.join()
            assertFalse(registry.hasRegisteredExecution("hidden-session"))
            registry.clearBridge(bridge, "hidden-session", registerActiveBridge = false)
        }

    @Test
    fun `stale hidden bridge cleanup preserves replacement execution`() =
        runTest {
            val registry =
                ActiveScanRegistry(coordinatorTimelineSource(FakeDiagnosticsHistoryStores(), backgroundScope))
            val staleBridge = FakeNetworkDiagnosticsBridge(json)
            val replacementBridge = FakeNetworkDiagnosticsBridge(json)
            registry.registerBridge(staleBridge, "hidden-session", registerActiveBridge = false)
            registry.registerBridge(replacementBridge, "hidden-session", registerActiveBridge = false)

            registry.clearBridge(staleBridge, "hidden-session", registerActiveBridge = false)

            assertTrue(registry.hasHiddenActiveScan)
            registry.clearBridge(replacementBridge, "hidden-session", registerActiveBridge = false)
            assertFalse(registry.hasHiddenActiveScan)
        }

    @Test
    fun `hidden cancellation propagates caller cancellation from preparation`() =
        runTest {
            val registry =
                ActiveScanRegistry(coordinatorTimelineSource(FakeDiagnosticsHistoryStores(), backgroundScope))
            val bridge = FakeNetworkDiagnosticsBridge(json)
            val executionJob = backgroundScope.launch { awaitCancellation() }
            val preparationStarted = CompletableDeferred<Unit>()
            registry.registerBridge(bridge, "hidden-session", registerActiveBridge = false)
            registry.registerExecution("hidden-session", executionJob, registerActiveBridge = false)

            val caller =
                launch {
                    registry.cancelHiddenAutomaticProbe("cancelled", timeoutMs = 1_000L) {
                        preparationStarted.complete(Unit)
                        awaitCancellation()
                    }
                }
            preparationStarted.await()
            caller.cancelAndJoin()

            assertTrue(caller.isCancelled)
            assertTrue(executionJob.isActive)
            executionJob.cancelAndJoin()
            registry.clearBridge(bridge, "hidden-session", registerActiveBridge = false)
        }

    @Test
    fun `hidden cancellation propagates caller cancellation while joining execution`() =
        runTest {
            val registry =
                ActiveScanRegistry(coordinatorTimelineSource(FakeDiagnosticsHistoryStores(), backgroundScope))
            val bridge = FakeNetworkDiagnosticsBridge(json)
            val cleanupStarted = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val executionJob =
                backgroundScope.launch {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleanupStarted.complete(Unit)
                            releaseCleanup.await()
                        }
                    }
                }
            registry.registerBridge(bridge, "hidden-session", registerActiveBridge = false)
            registry.registerExecution("hidden-session", executionJob, registerActiveBridge = false)

            val caller =
                launch {
                    registry.cancelHiddenAutomaticProbe("cancelled", timeoutMs = 1_000L)
                }
            cleanupStarted.await()
            caller.cancelAndJoin()

            assertTrue(caller.isCancelled)
            releaseCleanup.complete(Unit)
            executionJob.join()
            registry.clearBridge(bridge, "hidden-session", registerActiveBridge = false)
        }

    @Test
    fun `session ownership tracks and releases sessions by run`() {
        val ownership = ScanSessionOwnership()
        ownership.remember("session-one", "run-one")
        ownership.remember("session-two", "run-one")
        ownership.remember("session-three", "run-two")

        assertEquals(setOf("session-one", "session-two"), ownership.activeSessionIds("run-one"))

        ownership.remove("session-one")
        assertEquals(setOf("session-two"), ownership.activeSessionIds("run-one"))
    }
}
