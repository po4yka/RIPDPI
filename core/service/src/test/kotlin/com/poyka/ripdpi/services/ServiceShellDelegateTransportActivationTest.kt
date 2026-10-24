package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RuntimeAppliedIntent
import com.poyka.ripdpi.data.stopAction
import com.poyka.ripdpi.data.testPauseAuthority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceShellDelegateTransportActivationTest {
    @Test
    fun `explicit transport activation retains captured authority across suspension`() =
        runTest {
            val fixture = ActivationFixture(this)
            val resume = CompletableDeferred<Unit>()
            val seen = mutableListOf<RuntimeAppliedIntent?>()
            val delegate =
                fixture.delegate {
                    seen += currentCoroutineContext()[RuntimeCommandStartAuthority]?.original
                    resume.await()
                    seen += currentCoroutineContext()[RuntimeCommandStartAuthority]?.original
                }

            fixture.deliver(delegate)
            runCurrent()
            assertEquals(1, seen.size)
            assertSame(fixture.activation, seen.single())
            resume.complete(Unit)
            runCurrent()
            assertEquals(2, seen.size)
            assertSame(fixture.activation, seen.last())
            assertTrue(fixture.rejected.isEmpty())
        }

    @Test
    fun `explicit transport activation rejects missing captured authority`() =
        runTest {
            val fixture = ActivationFixture(this)
            var activated = false
            val delegate = fixture.delegate { activated = true }

            fixture.deliver(delegate, activation = null)
            runCurrent()

            assertEquals(false, activated)
            assertEquals(listOf(17L), fixture.rejected)
        }

    @Test
    fun `queued explicit transport activation rejects superseded authority`() =
        runTest {
            val fixture = ActivationFixture(this)
            var activated = false
            val delegate = fixture.delegate { activated = true }

            fixture.deliver(delegate)
            fixture.authority.reserveStop()
            runCurrent()

            assertEquals(false, activated)
            assertEquals(listOf(17L), fixture.rejected)
        }

    @Test
    fun `user stop cancels and rejects active explicit transport activation`() =
        runTest {
            val fixture = ActivationFixture(this)
            val suspended = CompletableDeferred<Unit>()
            var cancelled = false
            val delegate =
                fixture.delegate {
                    try {
                        suspended.await()
                    } finally {
                        cancelled = true
                    }
                }

            fixture.deliver(delegate)
            runCurrent()
            TestCapturedServiceCommand.stop(fixture.authority, fixture.arbiter).deliver(delegate, stopAction, 2)
            runCurrent()

            assertTrue(cancelled)
            assertEquals(listOf(17L), fixture.rejected)
        }

    @Test
    fun `durable authority change cancels suspended explicit activation`() =
        runTest {
            val fixture = ActivationFixture(this)
            val suspended = CompletableDeferred<Unit>()
            var cancelled = false
            val delegate =
                fixture.delegate {
                    try {
                        suspended.await()
                    } finally {
                        cancelled = true
                    }
                }

            fixture.deliver(delegate)
            runCurrent()
            fixture.authority.reserveStop()
            runCurrent()

            assertTrue(cancelled)
        }

    private class ActivationFixture(
        private val scope: TestScope,
    ) {
        val authority = testPauseAuthority()
        val arbiter = ServiceIntentArbiter(authority)
        private val receipt = authority.reserveStart(Mode.VPN)
        private val lease = checkNotNull(arbiter.dispatchExplicit(receipt))
        val activation = RuntimeAppliedIntent.Activation(receipt)
        val rejected = mutableListOf<Long>()

        fun delegate(activate: suspend () -> Unit) =
            ServiceShellDelegate(
                serviceIntentArbiter = arbiter,
                serviceScope = scope.backgroundScope,
                serviceLabel = "vpn",
                onStart = {},
                onStop = { _, _ -> },
                transportFailoverCommandHandler =
                    TransportFailoverCommandHandler(
                        restart = { _, _ -> error("Explicit activation used automatic restart") },
                        activate = { _, _ -> activate() },
                        reject = rejected::add,
                    ),
                ioDispatcher = StandardTestDispatcher(scope.testScheduler),
                intentCallbacks = testShellIntentCallbacks(authority::reference),
            )

        fun deliver(
            delegate: ServiceShellDelegate,
            activation: RuntimeAppliedIntent? = this.activation,
        ) = delegate.onStartCommand(
            transportActivationStartAction,
            1,
            transportFailoverRequestId = 17L,
            transportFailoverTarget = TransportFailoverTarget(RelayKindVlessReality, "reality-1"),
            explicitUserIntentGeneration = lease.processGeneration,
            durableReference = receipt.authority,
            activation = activation,
            stopSnapshot = null,
        )
    }
}
