package com.poyka.ripdpi.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkDiagnosticsLockingTest {
    @Test
    fun cancelScanNotBlockedByPollProgress() =
        runTest {
            val progressStarted = CompletableDeferred<Long>()
            val progressBlocker = CompletableDeferred<Unit>()
            val cancelStarted = CompletableDeferred<Long>()
            val bindings =
                FakeNetworkDiagnosticsBindings().apply {
                    progressJson = """{"phase":"running"}"""
                    progressStartedSignal = progressStarted
                    this.progressBlocker = progressBlocker
                    cancelStartedSignal = cancelStarted
                }
            val diagnostics = NetworkDiagnostics(bindings)
            diagnostics.startScan(requestJson = "{}", sessionId = "scan-1")

            val pollJob = async { diagnostics.pollProgressJson() }
            assertEquals(1L, progressStarted.await())

            val cancelJob = async { diagnostics.cancelScan() }

            assertEquals(1L, cancelStarted.await())
            cancelJob.await()
            assertFalse(pollJob.isCompleted)

            progressBlocker.complete(Unit)

            assertEquals("""{"phase":"running"}""", pollJob.await())
            assertEquals(listOf(1L), bindings.cancelledHandles)
        }

    @Test
    fun destroyWaitsForInFlightPolls() =
        runTest {
            val progressStarted = CompletableDeferred<Long>()
            val progressBlocker = CompletableDeferred<Unit>()
            val destroyStarted = CompletableDeferred<Long>()
            val bindings =
                FakeNetworkDiagnosticsBindings().apply {
                    progressJson = """{"phase":"running"}"""
                    progressStartedSignal = progressStarted
                    this.progressBlocker = progressBlocker
                    destroyStartedSignal = destroyStarted
                }
            val diagnostics = NetworkDiagnostics(bindings)
            diagnostics.startScan(requestJson = "{}", sessionId = "scan-1")

            val pollJob = async { diagnostics.pollProgressJson() }
            assertEquals(1L, progressStarted.await())

            val destroyJob = async { diagnostics.destroy() }
            runCurrent()

            assertFalse(destroyJob.isCompleted)
            assertFalse(destroyStarted.isCompleted)

            progressBlocker.complete(Unit)

            assertEquals("""{"phase":"running"}""", pollJob.await())
            destroyJob.await()
            assertEquals(1L, destroyStarted.await())
            assertEquals(listOf(1L), bindings.destroyedHandles)
        }

    @Test
    fun lazyHandleCreatedExactlyOnce() =
        runTest {
            val bindings =
                FakeNetworkDiagnosticsBindings().apply {
                    progressJson = """{"phase":"idle"}"""
                    reportJson = """{"status":"complete"}"""
                    passiveEventsJson = """[]"""
                }
            val diagnostics = NetworkDiagnostics(bindings)

            assertEquals("""{"phase":"idle"}""", diagnostics.pollProgressJson())
            assertEquals("""{"status":"complete"}""", diagnostics.takeReportJson())
            assertEquals("""[]""", diagnostics.pollPassiveEventsJson())

            assertEquals(1, bindings.createCount)
            assertEquals(listOf(1L), bindings.progressHandles)
            assertEquals(listOf(1L), bindings.reportHandles)
            assertEquals(listOf(1L), bindings.passiveEventHandles)
        }
}
