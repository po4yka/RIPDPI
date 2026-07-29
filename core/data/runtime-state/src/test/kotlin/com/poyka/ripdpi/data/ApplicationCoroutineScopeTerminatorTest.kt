package com.poyka.ripdpi.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private fun CoroutineScope.cancellableChild(completed: CompletableDeferred<Unit>) =
        launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                completed.complete(Unit)
            }
        }
}
