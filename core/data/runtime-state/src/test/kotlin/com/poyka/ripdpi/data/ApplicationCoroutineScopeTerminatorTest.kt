package com.poyka.ripdpi.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationCoroutineScopeTerminatorTest {
    @Test
    fun `cancels and joins both root scopes idempotently`() {
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val applicationIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val applicationChildCompleted = CompletableDeferred<Unit>()
        val applicationIoChildCompleted = CompletableDeferred<Unit>()
        val applicationChild = applicationScope.cancellableChild(applicationChildCompleted)
        val applicationIoChild = applicationIoScope.cancellableChild(applicationIoChildCompleted)
        val terminator = ApplicationCoroutineScopeTerminator(applicationScope, applicationIoScope)

        terminator.terminate()
        terminator.terminate()

        assertTrue(applicationChild.isCompleted)
        assertTrue(applicationIoChild.isCompleted)
        assertTrue(applicationChildCompleted.isCompleted)
        assertTrue(applicationIoChildCompleted.isCompleted)
    }

    @Test(timeout = 2_000)
    fun `returns with roots cancelled when child finalization needs a paused dispatcher`() =
        runTest {
            val pausedDispatcher = StandardTestDispatcher(testScheduler)
            val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val applicationIoScope = CoroutineScope(SupervisorJob() + pausedDispatcher)
            val childCompleted = CompletableDeferred<Unit>()
            val child = applicationIoScope.cancellableChild(childCompleted)
            val terminator = ApplicationCoroutineScopeTerminator(applicationScope, applicationIoScope)

            terminator.terminate()

            assertTrue(applicationScope.coroutineContext[Job]!!.isCancelled)
            assertTrue(applicationIoScope.coroutineContext[Job]!!.isCancelled)
            assertFalse(child.isCompleted)
            runCurrent()
            assertTrue(child.isCompleted)
            assertTrue(childCompleted.isCompleted)
        }

    @Test(timeout = 2_000)
    fun `returns with roots cancelled when child defers cancellation`() =
        runBlocking {
            val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val applicationIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val releaseChild = CompletableDeferred<Unit>()
            val childStarted = CompletableDeferred<Unit>()
            val child =
                applicationIoScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    childStarted.complete(Unit)
                    withContext(NonCancellable) { releaseChild.await() }
                }
            val terminator = ApplicationCoroutineScopeTerminator(applicationScope, applicationIoScope)

            terminator.terminate()

            assertTrue(childStarted.isCompleted)
            assertTrue(applicationScope.coroutineContext[Job]!!.isCancelled)
            assertTrue(applicationIoScope.coroutineContext[Job]!!.isCancelled)
            assertFalse(child.isCompleted)
            releaseChild.complete(Unit)
            withTimeout(1_000) { child.join() }
        }

    private fun CoroutineScope.cancellableChild(completed: CompletableDeferred<Unit>) =
        launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                completed.complete(Unit)
            }
        }
}
