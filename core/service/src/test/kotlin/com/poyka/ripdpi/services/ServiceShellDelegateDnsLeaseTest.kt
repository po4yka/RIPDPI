package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.startAction
import com.poyka.ripdpi.data.stopAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceShellDelegateDnsLeaseTest {
    @Test
    fun `vpn reservation survives initial halted state until command finishes`() =
        runTest {
            val arbiter = ServiceIntentArbiter()
            val continueStart = CompletableDeferred<Unit>()
            arbiter.dispatchVpnStart { ServiceStartResult.Accepted(Mode.VPN) }
            val generation = arbiter.captureVpnStartGeneration()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { continueStart.await() },
                    onStop = { _, _ -> },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onStartCommand(diagnosticsStartAction, 1, vpnStartGeneration = generation)
            runCurrent()
            assertNull(arbiter.tryReserveDoqSave { true })
            continueStart.complete(Unit)
            runCurrent()
            assertNotNull(arbiter.tryReserveDoqSave { true }?.also(AutoCloseable::close))
        }

    @Test
    fun `failed vpn command releases only its own reservation`() =
        runTest {
            val arbiter = ServiceIntentArbiter()
            arbiter.dispatchVpnStart { ServiceStartResult.Accepted(Mode.VPN) }
            val generation = arbiter.captureVpnStartGeneration()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { error("startup failure") },
                    onStop = { _, _ -> },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onStartCommand(diagnosticsStartAction, 1, vpnStartGeneration = generation)
            runCurrent()
            assertNotNull(arbiter.tryReserveDoqSave { true }?.also(AutoCloseable::close))
        }

    @Test
    fun `old service command cannot release a newer vpn start`() =
        runTest {
            val arbiter = ServiceIntentArbiter()
            val continueOldStart = CompletableDeferred<Unit>()
            val continueNewStart = CompletableDeferred<Unit>()
            var starts = 0
            arbiter.dispatchVpnStart { ServiceStartResult.Accepted(Mode.VPN) }
            val oldGeneration = arbiter.captureVpnStartGeneration()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {
                        starts++
                        if (starts == 1) continueOldStart.await() else continueNewStart.await()
                    },
                    onStop = { _, _ -> },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
            delegate.onStartCommand(diagnosticsStartAction, 1, vpnStartGeneration = oldGeneration)
            runCurrent()

            arbiter.dispatchVpnStart { ServiceStartResult.Accepted(Mode.VPN) }
            val newGeneration = arbiter.captureVpnStartGeneration()
            delegate.onStartCommand(diagnosticsStartAction, 2, vpnStartGeneration = newGeneration)
            continueOldStart.complete(Unit)
            runCurrent()

            assertEquals(2, starts)
            assertNull(arbiter.tryReserveDoqSave { true })
            continueNewStart.complete(Unit)
            runCurrent()
            assertNotNull(arbiter.tryReserveDoqSave { true }?.also(AutoCloseable::close))
        }

    @Test
    fun `rejected newer intent cannot release older running vpn command`() =
        runTest {
            val arbiter = ServiceIntentArbiter()
            val continueOldStart = CompletableDeferred<Unit>()
            arbiter.dispatchVpnStart { ServiceStartResult.Accepted(Mode.VPN) }
            val oldGeneration = arbiter.captureVpnStartGeneration()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { continueOldStart.await() },
                    onStop = { _, _ -> },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
            delegate.onStartCommand(diagnosticsStartAction, 1, vpnStartGeneration = oldGeneration)
            runCurrent()

            arbiter.dispatchVpnStart { ServiceStartResult.Accepted(Mode.VPN) }
            val newGeneration = arbiter.captureVpnStartGeneration()
            delegate.onStartCommand(
                startAction,
                2,
                explicitUserIntentGeneration = -1L,
                vpnStartGeneration = newGeneration,
            )
            assertNull(arbiter.tryReserveDoqSave { true })

            continueOldStart.complete(Unit)
            runCurrent()
            assertNotNull(arbiter.tryReserveDoqSave { true }?.also(AutoCloseable::close))
        }

    @Test
    fun `two accepted intents before service creation keep separate reservations`() =
        runTest {
            val arbiter = ServiceIntentArbiter()
            arbiter.dispatchVpnStart { ServiceStartResult.Accepted(Mode.VPN) }
            val firstGeneration = arbiter.captureVpnStartGeneration()
            arbiter.dispatchVpnStart { ServiceStartResult.Accepted(Mode.VPN) }
            val secondGeneration = arbiter.captureVpnStartGeneration()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = { _, _ -> },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onStartCommand(
                startAction,
                2,
                explicitUserIntentGeneration = -1L,
                vpnStartGeneration = secondGeneration,
            )
            assertNull(arbiter.tryReserveDoqSave { true })
            delegate.onStartCommand(diagnosticsStartAction, 1, vpnStartGeneration = firstGeneration)
            runCurrent()
            assertNotNull(arbiter.tryReserveDoqSave { true }?.also(AutoCloseable::close))
        }

    @Test
    fun `stop cancels an active vpn start before releasing its reservation`() =
        runTest {
            val arbiter = ServiceIntentArbiter()
            val continueStart = CompletableDeferred<Unit>()
            arbiter.dispatchVpnStart { ServiceStartResult.Accepted(Mode.VPN) }
            val generation = arbiter.captureVpnStartGeneration()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { continueStart.await() },
                    onStop = { _, _ -> },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

            delegate.onStartCommand(diagnosticsStartAction, 1, vpnStartGeneration = generation)
            runCurrent()
            delegate.onStartCommand(stopAction, 2, explicitUserIntentGeneration = 0L)
            runCurrent()

            assertNotNull(arbiter.tryReserveDoqSave { true }?.also(AutoCloseable::close))
        }
}
