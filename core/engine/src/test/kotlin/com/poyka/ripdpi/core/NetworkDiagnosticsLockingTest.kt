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

    @Test
    fun `take report consumes native result exactly once`() =
        runTest {
            val bindings = FakeNetworkDiagnosticsBindings().apply { reportJson = """{\"status\":\"complete\"}""" }
            val diagnostics = NetworkDiagnostics(bindings)

            assertEquals("""{\"status\":\"complete\"}""", diagnostics.takeReportJson())
            assertEquals(null, diagnostics.takeReportJson())
            assertEquals(listOf(1L, 1L), bindings.reportHandles)
        }

    @Test
    fun destroyDrainsAllInFlightPolls() =
        runTest {
            val progressStarted = CompletableDeferred<Long>()
            val progressBlocker = CompletableDeferred<Unit>()
            val reportStarted = CompletableDeferred<Long>()
            val reportBlocker = CompletableDeferred<Unit>()
            val passiveStarted = CompletableDeferred<Long>()
            val passiveBlocker = CompletableDeferred<Unit>()
            val destroyStarted = CompletableDeferred<Long>()
            val bindings =
                FakeNetworkDiagnosticsBindings().apply {
                    progressJson = """{"phase":"running"}"""
                    reportJson = """{"status":"partial"}"""
                    passiveEventsJson = """[]"""
                    progressStartedSignal = progressStarted
                    this.progressBlocker = progressBlocker
                    reportStartedSignal = reportStarted
                    this.reportBlocker = reportBlocker
                    passiveEventsStartedSignal = passiveStarted
                    this.passiveEventsBlocker = passiveBlocker
                    destroyStartedSignal = destroyStarted
                }
            val diagnostics = NetworkDiagnostics(bindings)

            val progressJob = async { diagnostics.pollProgressJson() }
            val reportJob = async { diagnostics.takeReportJson() }
            val passiveJob = async { diagnostics.pollPassiveEventsJson() }
            assertEquals(1L, progressStarted.await())
            assertEquals(1L, reportStarted.await())
            assertEquals(1L, passiveStarted.await())

            val destroyJob = async { diagnostics.destroy() }
            runCurrent()
            assertFalse(destroyJob.isCompleted)

            // Releasing two of the three reservations is not enough: destroy
            // waits for every in-flight poll before retiring the handle.
            progressBlocker.complete(Unit)
            reportBlocker.complete(Unit)
            runCurrent()
            assertFalse(destroyJob.isCompleted)
            assertFalse(destroyStarted.isCompleted)

            passiveBlocker.complete(Unit)
            progressJob.await()
            reportJob.await()
            passiveJob.await()
            destroyJob.await()
            assertEquals(1L, destroyStarted.await())
            assertEquals(listOf(1L), bindings.destroyedHandles.toList())
        }

    @Test
    fun cancelledPollReleasesReservationForDestroy() =
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

            // Cancel the poll caller while its reservation is in flight.
            val pollJob = async { diagnostics.pollProgressJson() }
            assertEquals(1L, progressStarted.await())
            pollJob.cancel()
            progressBlocker.complete(Unit)
            pollJob.join()

            // The reservation was released despite cancellation, so destroy
            // drains and retires the handle instead of wedging.
            diagnostics.destroy()
            assertEquals(1L, destroyStarted.await())
            assertEquals(listOf(1L), bindings.destroyedHandles.toList())
        }
}
