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
class DiagnosticsHomeRunLeaseTest {
    @Test
    fun `lease holds through interstage work and finalization then admits the next run`() =
        runTest {
            val lease = DiagnosticsHomeRunLease()
            val jobs = HomeCompositeRunJobs(backgroundScope, lease)
            val finishFinalization = CompletableDeferred<Unit>()
            assertTrue(jobs.launch("first", onFailure = { throw it }) { finishFinalization.await() })
            runCurrent()

            assertTrue(lease.isActive())
            assertTrue(lease.permits("first"))
            assertFalse(lease.permits(null))
            assertFalse(lease.permits("other"))
            assertFalse(jobs.launch("other", onFailure = { throw it }) { error("overlap") })

            finishFinalization.complete(Unit)
            runCurrent()
            assertFalse(lease.isActive())
            assertFalse(lease.permits("first"))
            assertTrue(jobs.launch("next", onFailure = { throw it }) { awaitCancellation() })
            lease.release("first")
            assertTrue(lease.isOwnedBy("next"))
        }

    @Test
    fun `failed run retains lease until failure cleanup ends`() =
        runTest {
            val lease = DiagnosticsHomeRunLease()
            val jobs = HomeCompositeRunJobs(backgroundScope, lease)
            val cleanupStarted = CompletableDeferred<Unit>()
            val finishCleanup = CompletableDeferred<Unit>()
            assertTrue(
                jobs.launch("failed", onFailure = {
                    cleanupStarted.complete(Unit)
                    finishCleanup.await()
                }) { error("stage failed") },
            )
            cleanupStarted.await()
            assertTrue(lease.isActive())
            assertFalse(lease.permits(null))
            finishCleanup.complete(Unit)
            runCurrent()
            assertFalse(lease.isActive())
        }

    @Test
    fun `startup cancellation retains lease until teardown and cannot clear a replacement owner`() =
        runTest {
            val lease = DiagnosticsHomeRunLease()
            val jobs = HomeCompositeRunJobs(backgroundScope, lease)
            val reserved = CompletableDeferred<Unit>()
            val startup =
                backgroundScope.async {
                    assertTrue(jobs.reserve("startup"))
                    reserved.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        jobs.releaseReservation("startup")
                    }
                }
            reserved.await()
            val teardownStarted = CompletableDeferred<Unit>()
            val finishTeardown = CompletableDeferred<Unit>()
            val cancellation =
                backgroundScope.async {
                    jobs.cancel("startup") {
                        teardownStarted.complete(Unit)
                        finishTeardown.await()
                    }
                }
            teardownStarted.await()
            assertTrue(startup.isCancelled)
            assertTrue(lease.isActive())
            assertFalse(lease.acquire("overlap"))
            finishTeardown.complete(Unit)
            assertTrue(cancellation.await())
            assertFalse(lease.isActive())
            assertTrue(lease.acquire("replacement"))
            jobs.releaseReservation("startup")
            assertTrue(lease.isOwnedBy("replacement"))
        }

    @Test
    fun `reservation waits for an admitted profile mutation and sees its final active-scan state`() =
        runTest {
            val lease = DiagnosticsHomeRunLease()
            val mutationStarted = CompletableDeferred<Unit>()
            val finishMutation = CompletableDeferred<Unit>()
            var scanActive = false
            val mutation =
                backgroundScope.async {
                    lease.withAdmission {
                        mutationStarted.complete(Unit)
                        finishMutation.await()
                        scanActive = true
                    }
                }
            mutationStarted.await()
            val reservation = backgroundScope.async { lease.acquire("home") { !scanActive } }
            runCurrent()
            assertFalse(reservation.isCompleted)
            finishMutation.complete(Unit)
            mutation.await()
            assertFalse(reservation.await())
            assertFalse(lease.isActive())
        }
}
