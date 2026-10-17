package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.services.ServiceStartRejectionReason
import com.poyka.ripdpi.services.ServiceStartResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConfigRuntimeResultFeedbackTest {
    @Test
    fun `accepted start signals only its first actual ready result`() =
        runTest {
            val fixture = ConfigFeedbackFixture(backgroundScope)
            fixture.actions.toggle(Mode.Proxy, true)
            runCurrent()
            assertEquals(0, fixture.connected)
            fixture.service.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            fixture.service.setStatus(AppStatus.Reconnecting, Mode.Proxy)
            runCurrent()
            fixture.service.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            assertEquals(1, fixture.connected)
            assertTrue(fixture.failures.isEmpty())
        }

    @Test
    fun `optional authority failure preserves the actual running result without an error signal`() =
        runTest {
            val fixture = ConfigFeedbackFixture(backgroundScope)
            fixture.actions.toggle(Mode.Proxy, true)
            runCurrent()
            fixture.controller.nextCaptureFailure = IOException("Authority unavailable")
            fixture.service.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            assertEquals(AppStatus.Running to Mode.Proxy, fixture.service.status.value)
            assertEquals(null, fixture.actions.feedback.value.operation)
            assertEquals(null, fixture.actions.feedback.value.error)
            assertEquals(0, fixture.connected)
            assertTrue(fixture.failures.isEmpty())
            fixture.service.setStatus(AppStatus.Reconnecting, Mode.Proxy)
            runCurrent()
            fixture.service.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            assertEquals(0, fixture.connected)
            assertTrue(fixture.failures.isEmpty())
            assertEquals(null, fixture.controller.nextCaptureFailure)

            fixture.service.setStatus(AppStatus.Halted, Mode.Proxy)
            runCurrent()
            fixture.actions.toggle(Mode.Proxy, true)
            runCurrent()
            fixture.service.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            assertEquals(1, fixture.connected)
            assertEquals(null, fixture.actions.feedback.value.error)
            assertTrue(fixture.failures.isEmpty())
        }

    @Test
    fun `initial running and a stop operation never signal connection success`() =
        runTest {
            val fixture = ConfigFeedbackFixture(backgroundScope, FakeServiceStateStore(AppStatus.Running to Mode.Proxy))
            runCurrent()
            assertEquals(0, fixture.connected)
            fixture.actions.toggle(Mode.Proxy, false)
            fixture.service.setStatus(AppStatus.Halted, Mode.Proxy)
            runCurrent()
            assertEquals(0, fixture.connected)
            assertTrue(fixture.failures.isEmpty())
        }

    @Test
    fun `rejected start signals one local failure and a late status stays quiet`() =
        runTest {
            val fixture = ConfigFeedbackFixture(backgroundScope)
            fixture.controller.nextStartResult =
                ServiceStartResult.Rejected(Mode.Proxy, ServiceStartRejectionReason.Superseded)
            fixture.actions.toggle(Mode.Proxy, true)
            runCurrent()
            fixture.service.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            assertEquals(1, fixture.failures.size)
            assertEquals(0, fixture.connected)
        }

    @Test
    fun `async failure stays owned by the global service error signal`() =
        runTest {
            val fixture = ConfigFeedbackFixture(backgroundScope)
            fixture.actions.toggle(Mode.Proxy, true)
            runCurrent()
            fixture.service.emitFailed(Sender.Proxy, FailureReason.NativeError("Failed"))
            runCurrent()
            fixture.service.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            assertEquals(0, fixture.connected)
            assertTrue(fixture.failures.isEmpty())
            assertEquals(null, fixture.actions.feedback.value.operation)
        }

    @Test
    fun `timeout emits one local error and never accepts a late running status`() =
        runTest {
            val fixture = ConfigFeedbackFixture(backgroundScope)
            fixture.actions.toggle(Mode.Proxy, true)
            runCurrent()
            advanceTimeBy(30_001)
            runCurrent()
            fixture.service.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            assertEquals(1, fixture.failures.size)
            assertEquals(0, fixture.connected)
        }

    @Test
    fun `cancelled or superseded dispatch cannot signal success`() =
        runTest {
            val fixture = ConfigFeedbackFixture(backgroundScope)
            val request = fixture.actions.toggle(Mode.Proxy, true)
            runCurrent()
            request?.cancel()
            runCurrent()
            fixture.service.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            assertEquals(0, fixture.connected)
            assertTrue(fixture.failures.isEmpty())

            fixture.service.setStatus(AppStatus.Halted, Mode.Proxy)
            runCurrent()
            fixture.actions.toggle(Mode.Proxy, true)
            runCurrent()
            fixture.controller.prepareStop()
            fixture.service.setStatus(AppStatus.Running, Mode.Proxy)
            runCurrent()
            assertEquals(0, fixture.connected)
        }

    @Test
    fun `dispatch exception preserves one failure message`() =
        runTest {
            val fixture = ConfigFeedbackFixture(backgroundScope)
            val saved = CompletableDeferred<Unit>()
            saved.completeExceptionally(IOException("Save failed"))
            fixture.actions.toggle(Mode.Proxy, true) { saved.await() }
            runCurrent()
            assertEquals(1, fixture.failures.size)
            assertEquals(0, fixture.connected)
        }
}

private class ConfigFeedbackFixture(
    scope: CoroutineScope,
    val service: FakeServiceStateStore = FakeServiceStateStore(),
) {
    val controller = FakeServiceController()
    var connected = 0
    val failures = mutableListOf<String>()
    val actions =
        ConfigRuntimeActions(
            scope,
            controller,
            service,
            ResourceStringResolver(),
            onRuntimeConnected = { connected += 1 },
            onRuntimeFailed = { failures += it },
        )
}
