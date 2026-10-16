package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.Sender
import com.poyka.ripdpi.services.ServiceStartRejectionReason
import com.poyka.ripdpi.services.ServiceStartResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConfigRuntimeActionsTest {
    @Test
    fun `start stays pending until the requested mode confirms readiness`() =
        runTest {
            Mode.entries.forEach { mode ->
                val service = FakeServiceStateStore()
                val controller = FakeServiceController()
                val actions = ConfigRuntimeActions(backgroundScope, controller, service, ResourceStringResolver())

                actions.toggle(mode, enabled = true)
                runCurrent()
                assertEquals(ConfigRuntimeOperation.Starting, actions.feedback.value.operation)
                advanceTimeBy(29_999)
                assertEquals(ConfigRuntimeOperation.Starting, actions.feedback.value.operation)

                service.setStatus(AppStatus.Running, mode)
                runCurrent()
                assertNull(actions.feedback.value.operation)
                assertNull(actions.feedback.value.error)
            }
        }

    @Test
    fun `starting the other mode does not treat the previous running mode as ready`() =
        runTest {
            Mode.entries.forEach { target ->
                val previous = Mode.entries.first { it != target }
                val service = FakeServiceStateStore(AppStatus.Running to previous)
                val controller = FakeServiceController()
                val actions = ConfigRuntimeActions(backgroundScope, controller, service, ResourceStringResolver())

                actions.toggle(target, enabled = true)
                actions.toggle(previous, enabled = true)
                runCurrent()

                assertEquals(listOf(target), controller.startedModes)
                assertEquals(ConfigRuntimeOperation.Starting, actions.feedback.value.operation)
                service.setStatus(AppStatus.Running, target)
                runCurrent()
                assertNull(actions.feedback.value.operation)
            }
        }

    @Test
    fun `stop ignores repeated taps and waits for halted acknowledgement`() =
        runTest {
            Mode.entries.forEach { mode ->
                val service = FakeServiceStateStore(AppStatus.Running to mode)
                val controller = FakeServiceController()
                val actions = ConfigRuntimeActions(backgroundScope, controller, service, ResourceStringResolver())

                actions.toggle(mode, enabled = false)
                actions.toggle(mode, enabled = false)
                runCurrent()

                assertEquals(1, controller.stopCount)
                assertEquals(ConfigRuntimeOperation.Stopping, actions.feedback.value.operation)
                service.setStatus(AppStatus.Halted, mode)
                runCurrent()
                assertNull(actions.feedback.value.operation)
            }
        }

    @Test
    fun `rejected consent shows a durable reason and permission recovery`() =
        runTest {
            val service = FakeServiceStateStore()
            val controller =
                FakeServiceController().apply {
                    nextStartResult =
                        ServiceStartResult.Rejected(Mode.VPN, ServiceStartRejectionReason.VpnConsentMissing)
                }
            val strings = ResourceStringResolver()
            val actions = ConfigRuntimeActions(backgroundScope, controller, service, strings)

            actions.toggle(Mode.VPN, enabled = true)
            runCurrent()

            assertNull(actions.feedback.value.operation)
            assertTrue(actions.feedback.value.requiresVpnPermission)
            assertTrue(
                actions.feedback.value.error
                    .orEmpty()
                    .contains(strings.getString(R.string.permissions_vpn_error_body)),
            )
            service.setStatus(AppStatus.Running, Mode.VPN)
            runCurrent()
            assertNull(actions.feedback.value.error)
            assertFalse(actions.feedback.value.requiresVpnPermission)
        }

    @Test
    fun `asynchronous startup failure releases the pending action and preserves the reason`() =
        runTest {
            val service = FakeServiceStateStore()
            val controller = FakeServiceController()
            val actions = ConfigRuntimeActions(backgroundScope, controller, service, ResourceStringResolver())

            actions.toggle(Mode.Proxy, enabled = true)
            runCurrent()
            service.emitFailed(Sender.Proxy, FailureReason.NativeError("Native start failed"))
            runCurrent()

            assertNull(actions.feedback.value.operation)
            assertEquals("Native start failed", actions.feedback.value.error)
            assertNotNull(actions.toggle(Mode.Proxy, enabled = true))
        }

    @Test
    fun `start timeout exposes a reason and permits another attempt`() =
        runTest {
            val service = FakeServiceStateStore()
            val controller = FakeServiceController()
            val strings = ResourceStringResolver()
            val actions = ConfigRuntimeActions(backgroundScope, controller, service, strings)

            actions.toggle(Mode.VPN, enabled = true)
            runCurrent()
            advanceTimeBy(30_001)
            runCurrent()

            assertNull(actions.feedback.value.operation)
            assertEquals(strings.getString(R.string.connection_timed_out), actions.feedback.value.error)
            actions.toggle(Mode.VPN, enabled = true)
            runCurrent()
            assertEquals(2, controller.startedModes.size)
            assertNull(actions.feedback.value.error)
        }

    @Test
    fun `stop timeout does not leave its action disabled indefinitely`() =
        runTest {
            val service = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val controller = FakeServiceController()
            val strings = ResourceStringResolver()
            val actions = ConfigRuntimeActions(backgroundScope, controller, service, strings)

            actions.toggle(Mode.VPN, enabled = false)
            runCurrent()
            advanceTimeBy(30_001)
            runCurrent()

            assertNull(actions.feedback.value.operation)
            assertEquals(strings.getString(R.string.runtime_config_stop_timeout), actions.feedback.value.error)
            assertNotNull(actions.toggle(Mode.VPN, enabled = false))
        }

    @Test
    fun `cancelling a request clears pending without publishing a failure`() =
        runTest {
            val service = FakeServiceStateStore()
            val controller = FakeServiceController()
            val actions = ConfigRuntimeActions(backgroundScope, controller, service, ResourceStringResolver())

            val request = actions.toggle(Mode.VPN, enabled = true)
            runCurrent()
            request?.cancel()
            runCurrent()

            assertNull(actions.feedback.value.operation)
            assertNull(actions.feedback.value.error)
            assertNotNull(actions.toggle(Mode.VPN, enabled = true))
        }

    @Test
    fun `external reconnect suppresses a competing user dispatch`() =
        runTest {
            val service = FakeServiceStateStore(AppStatus.Reconnecting to Mode.VPN)
            val controller = FakeServiceController()
            val actions = ConfigRuntimeActions(backgroundScope, controller, service, ResourceStringResolver())

            assertNull(actions.toggle(Mode.VPN, enabled = true))
            assertNull(actions.toggle(Mode.VPN, enabled = false))
            assertTrue(controller.startedModes.isEmpty())
            assertEquals(0, controller.stopCount)
        }

    @Test
    fun `pending saved configuration reconnect suppresses competing dispatch`() =
        runTest {
            val service = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val controller = FakeServiceController()
            val actions = ConfigRuntimeActions(backgroundScope, controller, service, ResourceStringResolver()) { true }

            assertNull(actions.toggle(Mode.Proxy, enabled = true))
            assertNull(actions.toggle(Mode.VPN, enabled = false))
            assertTrue(controller.startedModes.isEmpty())
            assertEquals(0, controller.stopCount)
        }

    @Test
    fun `superseded prepared stop clears pending without waiting for an acknowledgement`() =
        runTest {
            val service = FakeServiceStateStore(AppStatus.Running to Mode.VPN)
            val controller = FakeServiceController()
            val actions = ConfigRuntimeActions(backgroundScope, controller, service, ResourceStringResolver())

            actions.toggle(Mode.VPN, enabled = false) { controller.prepareStart(Mode.Proxy) }
            runCurrent()

            assertEquals(0, controller.stopCount)
            assertNull(actions.feedback.value.operation)
            assertNull(actions.feedback.value.error)
        }

    @Test
    fun `pending covers the editor save and prevents a second receipt from replacing it`() =
        runTest {
            val service = FakeServiceStateStore()
            val controller = FakeServiceController()
            val actions = ConfigRuntimeActions(backgroundScope, controller, service, ResourceStringResolver())
            val saved = CompletableDeferred<Unit>()

            actions.toggle(Mode.Proxy, enabled = true) { saved.await() }
            actions.toggle(Mode.VPN, enabled = true)
            runCurrent()
            assertTrue(controller.startedModes.isEmpty())
            assertEquals(ConfigRuntimeOperation.Starting, actions.feedback.value.operation)

            saved.complete(Unit)
            runCurrent()
            assertEquals(listOf(Mode.Proxy), controller.startedModes)
        }
}
