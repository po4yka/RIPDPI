package com.poyka.ripdpi.diagnostics.replay

import com.poyka.ripdpi.diagnostics.StrategyProbeActivationSnapshot
import com.poyka.ripdpi.diagnostics.StrategyProbeActivator
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidate
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateProvider
import com.poyka.ripdpi.diagnostics.StrategyProbeRunGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StrategyScopedProbeReplayTest {
    private val activator = FakeActivator()
    private val guard = StrategyProbeRunGuard()
    private val request = ReplayProbeRequest(domain = "example.org", strategyId = "split", timeoutMs = 1_000L)
    private val finished =
        ReplayStepEvent.Finished(ReplayVerdict.Success, ReplayStepKind.FirstByte, recommendationKey = "")

    private fun replay(delegate: ProbeReplayService) =
        StrategyScopedProbeReplay(
            delegate = delegate,
            candidateProvider =
                object : StrategyProbeCandidateProvider {
                    override suspend fun listCandidates(): List<StrategyProbeCandidate> =
                        listOf(StrategyProbeCandidate("split", "Split", "[tcp]\nsplit auto"))
                },
            activator = activator,
            runGuard = guard,
        )

    @Test
    fun `replay runs under the requested strategy and restores the original after success`() =
        runTest {
            var activeDuringRun: String? = null
            val events =
                replay(
                    ProbeReplayService {
                        flow {
                            activeDuringRun = activator.active
                            emit(finished)
                        }
                    },
                ).run(request).toList()

            assertEquals(listOf<ReplayStepEvent>(finished), events)
            assertEquals("split", activeDuringRun)
            assertEquals("previous", activator.active)
            assertFalse(guard.isHeld())
        }

    @Test
    fun `original strategy is restored when the replay fails`() =
        runTest {
            val failing = ProbeReplayService { flow { error("boom") } }

            try {
                replay(failing).run(request).toList()
                fail("expected failure")
            } catch (expected: IllegalStateException) {
                assertEquals("boom", expected.message)
            }

            assertEquals("previous", activator.active)
            assertFalse(guard.isHeld())
        }

    @Test
    fun `original strategy is restored when the collector is cancelled mid-run`() =
        runTest {
            val started = CompletableDeferred<String?>()
            val suspended =
                ProbeReplayService {
                    flow {
                        started.complete(activator.active)
                        kotlinx.coroutines.awaitCancellation()
                    }
                }

            val job = launch { replay(suspended).run(request).toList() }
            assertEquals("split", started.await())
            job.cancel()
            job.join()

            assertEquals("previous", activator.active)
            assertFalse(guard.isHeld())
        }

    @Test
    fun `unknown strategy id fails without activating anything`() =
        runTest {
            var ran = false
            val delegate = ProbeReplayService { flow { ran = true } }

            try {
                replay(delegate).run(request.copy(strategyId = "tlsrec_split_host")).toList()
                fail("expected failure")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("tlsrec_split_host"))
            }

            assertFalse(ran)
            assertEquals("previous", activator.active)
            assertEquals(0, activator.activations)
            assertFalse(guard.isHeld())
        }

    @Test
    fun `replay fails while another strategy run holds the guard and leaves it held`() =
        runTest {
            assertTrue(guard.tryAcquire())

            try {
                replay(ProbeReplayService { flow { emit(finished) } }).run(request).toList()
                fail("expected failure")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("already active"))
            }

            assertEquals(0, activator.activations)
            assertTrue(guard.isHeld())
        }

    private fun StrategyProbeRunGuard.isHeld(): Boolean =
        if (tryAcquire()) {
            release()
            false
        } else {
            true
        }

    private class FakeActivator : StrategyProbeActivator {
        var active: String = "previous"
        var activations = 0

        override suspend fun capture(): StrategyProbeActivationSnapshot = StrategyProbeActivationSnapshot(active)

        override suspend fun activate(candidate: StrategyProbeCandidate) {
            activations += 1
            active = candidate.id
        }

        override suspend fun restore(snapshot: StrategyProbeActivationSnapshot) {
            active = snapshot.snapshotId
        }
    }
}
