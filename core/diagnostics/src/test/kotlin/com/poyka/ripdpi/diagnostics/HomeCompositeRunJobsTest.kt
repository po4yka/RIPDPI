package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeCompositeRunJobsTest {
    @Test
    fun `previous completed run follows completion order across different and equal session counts`() {
        val oldLarge = homeOutcome("old-large", 3)
        val newSmall = homeOutcome("new-small", 1)
        val current = homeOutcome("current", 2)
        val differentCounts = HomeCompositeCompletionOrder()
        differentCounts.recordCompleted(oldLarge)
        differentCounts.recordCompleted(newSmall)

        assertEquals(newSmall, differentCounts.previousBefore(current.runId))
        differentCounts.recordCompleted(current)
        assertEquals(newSmall, differentCounts.previousBefore(current.runId))

        val oldTie = homeOutcome("old-tie", 2)
        val newTie = homeOutcome("new-tie", 2)
        val equalCounts = HomeCompositeCompletionOrder()
        equalCounts.recordCompleted(oldTie)
        equalCounts.recordCompleted(newTie)

        assertEquals(newTie, equalCounts.previousBefore(current.runId))
    }

    private fun homeOutcome(runId: String, sessionCount: Int): DiagnosticsHomeCompositeOutcome =
        DiagnosticsHomeCompositeOutcome(
            runId = runId,
            actionable = false,
            headline = runId,
            summary = runId,
            bundleSessionIds = List(sessionCount) { "$runId-$it" },
        )

    @Test
    fun `teardown keeps admission and executes once until cleanup completes`() =
        runTest {
            val jobs = HomeCompositeRunJobs(backgroundScope)
            val teardownStarted = CompletableDeferred<Unit>()
            val finishTeardown = CompletableDeferred<Unit>()
            assertTrue(jobs.launch("first", onFailure = { throw it }) { awaitCancellation() })

            val cancellation =
                backgroundScope.async {
                    jobs.cancel("first") {
                        teardownStarted.complete(Unit)
                        finishTeardown.await()
                    }
                }
            teardownStarted.await()

            assertFalse(jobs.launch("overlap", onFailure = { throw it }) { awaitCancellation() })
            assertFalse(jobs.cancel("first") { error("duplicate teardown") })

            finishTeardown.complete(Unit)
            cancellation.await()
            runCurrent()

            assertTrue(jobs.launch("next", onFailure = { throw it }) { awaitCancellation() })
        }
}
