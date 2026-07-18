package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeCompositeRunJobsTest {
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
