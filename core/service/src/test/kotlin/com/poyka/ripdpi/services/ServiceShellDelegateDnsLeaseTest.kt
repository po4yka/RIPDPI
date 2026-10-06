package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeAppliedIntent
import com.poyka.ripdpi.data.RuntimeAppliedUseIdentity
import com.poyka.ripdpi.data.RuntimeAppliedUseReceipt
import com.poyka.ripdpi.data.startAction
import com.poyka.ripdpi.data.stopAction
import com.poyka.ripdpi.data.testPauseAuthority
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
            val fixture = DnsLeaseCommandFixture()
            val arbiter = fixture.arbiter
            val continueStart = CompletableDeferred<Unit>()
            val start = fixture.captureVpnStart()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { continueStart.await() },
                    onStop = { _, _ -> },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks {
                            arbiter.durableReference()
                        },
                )

            start.deliver(
                delegate,
                diagnosticsStartAction,
                1,
            )
            runCurrent()
            assertNull(arbiter.tryReserveDoqSave { true })
            continueStart.complete(Unit)
            runCurrent()
            assertNotNull(arbiter.tryReserveDoqSave { true }?.also(AutoCloseable::close))
        }

    @Test
    fun `failed vpn command releases only its own reservation`() =
        runTest {
            val fixture = DnsLeaseCommandFixture()
            val arbiter = fixture.arbiter
            val start = fixture.captureVpnStart()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { error("startup failure") },
                    onStop = { _, _ -> },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks {
                            arbiter.durableReference()
                        },
                )

            start.deliver(
                delegate,
                diagnosticsStartAction,
                1,
            )
            runCurrent()
            assertNotNull(arbiter.tryReserveDoqSave { true }?.also(AutoCloseable::close))
        }

    @Test
    fun `old service command cannot release a newer vpn start`() =
        runTest {
            val fixture = DnsLeaseCommandFixture()
            val arbiter = fixture.arbiter
            val continueOldStart = CompletableDeferred<Unit>()
            val continueNewStart = CompletableDeferred<Unit>()
            var starts = 0
            val oldStart = fixture.captureVpnStart()
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
                    intentCallbacks =
                        testShellIntentCallbacks {
                            arbiter.durableReference()
                        },
                )
            oldStart.deliver(
                delegate,
                diagnosticsStartAction,
                1,
            )
            runCurrent()

            val newStart = fixture.captureVpnStart()
            newStart.deliver(
                delegate,
                diagnosticsStartAction,
                2,
            )
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
            val fixture = DnsLeaseCommandFixture()
            val arbiter = fixture.arbiter
            val continueOldStart = CompletableDeferred<Unit>()
            val oldStart = fixture.captureVpnStart()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { continueOldStart.await() },
                    onStop = { _, _ -> },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks {
                            arbiter.durableReference()
                        },
                )
            oldStart.deliver(
                delegate,
                diagnosticsStartAction,
                1,
            )
            runCurrent()

            val newStart = fixture.captureVpnStart()
            newStart.copy(processGeneration = -1L).deliver(
                delegate,
                startAction,
                2,
            )
            assertNull(arbiter.tryReserveDoqSave { true })

            continueOldStart.complete(Unit)
            runCurrent()
            assertNotNull(arbiter.tryReserveDoqSave { true }?.also(AutoCloseable::close))
        }

    @Test
    fun `two accepted intents before service creation keep separate reservations`() =
        runTest {
            val fixture = DnsLeaseCommandFixture()
            val arbiter = fixture.arbiter
            val firstStart = fixture.captureVpnStart()
            val secondStart = fixture.captureVpnStart()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = { _, _ -> },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks {
                            arbiter.durableReference()
                        },
                )

            secondStart.copy(processGeneration = -1L).deliver(
                delegate,
                startAction,
                2,
            )
            assertNull(arbiter.tryReserveDoqSave { true })
            firstStart.deliver(
                delegate,
                diagnosticsStartAction,
                1,
            )
            runCurrent()
            assertNotNull(arbiter.tryReserveDoqSave { true }?.also(AutoCloseable::close))
        }

    @Test
    fun `stop cancels an active vpn start before releasing its reservation`() =
        runTest {
            val fixture = DnsLeaseCommandFixture()
            val arbiter = fixture.arbiter
            val continueStart = CompletableDeferred<Unit>()
            val start = fixture.captureVpnStart()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter = arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = { continueStart.await() },
                    onStop = { _, _ -> },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks {
                            arbiter.durableReference()
                        },
                )

            start.deliver(
                delegate,
                diagnosticsStartAction,
                1,
            )
            runCurrent()
            val stop = TestCapturedServiceCommand.stop(fixture.authority, arbiter)
            stop.deliver(
                delegate,
                stopAction,
                2,
            )
            runCurrent()

            assertNotNull(arbiter.tryReserveDoqSave { true }?.also(AutoCloseable::close))
        }
}

/** Each DNS reservation carries the same checked session authority, captured before delivery. */
private data class CapturedDnsLeaseStart(
    val processGeneration: Long,
    val vpnStartGeneration: Long,
    val activation: RuntimeAppliedIntent.Continuation,
) {
    fun deliver(
        delegate: ServiceShellDelegate,
        action: String,
        startId: Int,
    ): Int =
        delegate.onStartCommand(
            action,
            startId,
            explicitUserIntentGeneration = processGeneration,
            vpnStartGeneration = vpnStartGeneration,
            durableReference = activation.receipt.authority,
            activation = activation,
            stopSnapshot = null,
        )
}

private class DnsLeaseCommandFixture {
    val authority = testPauseAuthority()
    val arbiter = ServiceIntentArbiter(authority)
    private val receipt = authority.reserveStart(Mode.VPN)
    private val lease = checkNotNull(arbiter.dispatchExplicit(receipt))
    private val positiveAck: RuntimeAppliedUseReceipt

    init {
        // The shell starts halted; its diagnostics continuations belong to an acknowledged session.
        val original = RuntimeAppliedIntent.Activation(receipt)
        val identity = RuntimeAppliedUseIdentity(receipt.commandId, 1, Mode.VPN.preferenceValue)
        check(authority.claimActivation(receipt, identity))
        check(
            authority.acknowledgeApplied(
                original,
                RuntimeAppliedUseReceipt(identity, emptyList(), 1_800_000_000_000L, 0, true, "0".repeat(64)),
            ),
        )
        positiveAck = checkNotNull(authority.acknowledgedAttempt(original, identity))
    }

    fun captureVpnStart(): CapturedDnsLeaseStart {
        val activation = RuntimeAppliedIntent.Continuation(receipt, positiveAck.identity)
        var command: CapturedDnsLeaseStart? = null
        val result =
            arbiter.dispatchVpnStart {
                command =
                    CapturedDnsLeaseStart(lease.processGeneration, arbiter.captureVpnStartGeneration(), activation)
                ServiceStartResult.Accepted(receipt)
            }
        check(result is ServiceStartResult.Accepted)
        return checkNotNull(command)
    }
}
