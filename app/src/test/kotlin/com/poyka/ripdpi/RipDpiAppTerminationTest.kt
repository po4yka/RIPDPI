package com.poyka.ripdpi

import android.app.Application
import com.poyka.ripdpi.data.ApplicationCoroutineScopeTerminator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RipDpiAppTerminationTest {
    @Test
    fun `application termination closes injected process scopes`() {
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val applicationIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val childCompleted = CompletableDeferred<Unit>()
        val child =
            applicationIoScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    childCompleted.complete(Unit)
                }
            }
        val application = RipDpiApp()
        application.applicationCoroutineScopeTerminator =
            ApplicationCoroutineScopeTerminator(applicationScope, applicationIoScope)

        application.onTerminate()

        assertTrue(child.isCompleted)
        assertTrue(childCompleted.isCompleted)
    }
}
