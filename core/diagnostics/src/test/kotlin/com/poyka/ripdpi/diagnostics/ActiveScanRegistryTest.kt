package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.testing.FaultOutcome
import com.poyka.ripdpi.core.testing.FaultSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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
