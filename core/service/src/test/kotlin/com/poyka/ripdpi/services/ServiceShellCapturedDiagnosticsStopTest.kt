package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeUserCommand
import com.poyka.ripdpi.data.startAction
import com.poyka.ripdpi.data.testPauseAuthority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceShellCapturedDiagnosticsStopTest {
    @Test fun `queued old raw diagnostic stop cannot stop a newer explicit start`() =
        runTest {
            val authority = testPauseAuthority()
            val arbiter = ServiceIntentArbiter(authority)
            val first =
                checkNotNull(arbiter.dispatchExplicit(authority.supersede(RuntimeUserCommand.Start(Mode.Proxy))))
            val entered = CompletableDeferred<Unit>()
            val hold = CompletableDeferred<Unit>()
            var starts = 0
            var stops = 0
            val shell =
                ServiceShellDelegate(
                    backgroundScope,
                    arbiter,
                    "proxy",
                    onStart = {
                        starts++
                        if (starts == 1) {
                            entered.complete(Unit)
                            hold.await()
                        }
                    },
                    onStop = { _, _ -> stops++ },
                    intentCallbacks = testShellIntentCallbacks(arbiter::durableReference),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
            shell.onStartCommand(
                startAction,
                1,
                explicitUserIntentGeneration = first.processGeneration,
                durableReference = first.durable.authority,
            )
            runCurrent()
            entered.await()
            shell.onStartCommand(
                diagnosticsStopAction,
                2,
                explicitUserIntentGeneration = first.processGeneration,
                durableReference = first.durable.authority,
            )
            val newer =
                checkNotNull(arbiter.dispatchExplicit(authority.supersede(RuntimeUserCommand.Start(Mode.Proxy))))
            shell.onStartCommand(
                startAction,
                3,
                explicitUserIntentGeneration = newer.processGeneration,
                durableReference = newer.durable.authority,
            )
            hold.complete(Unit)
            runCurrent()
            assertEquals(0, stops)
            assertEquals(2, starts)
            assertTrue(arbiter.isCurrent(newer))
            shell.close()
        }

    @Test fun `queued old diagnostic compensation cannot stop a newer pause`() =
        runTest {
            val authority = testPauseAuthority()
            val arbiter = ServiceIntentArbiter(authority)
            val first =
                checkNotNull(arbiter.dispatchExplicit(authority.supersede(RuntimeUserCommand.Start(Mode.Proxy))))
            val entered = CompletableDeferred<Unit>()
            val hold = CompletableDeferred<Unit>()
            var stops = 0
            val shell =
                ServiceShellDelegate(
                    backgroundScope,
                    arbiter,
                    "proxy",
                    onStart = {
                        entered.complete(Unit)
                        hold.await()
                    },
                    onStop = { _, _ -> stops++ },
                    isCompensatingStopCurrent = { true },
                    intentCallbacks = testShellIntentCallbacks(arbiter::durableReference),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
            shell.onStartCommand(
                startAction,
                1,
                explicitUserIntentGeneration = first.processGeneration,
                durableReference = first.durable.authority,
            )
            runCurrent()
            entered.await()
            shell.onStartCommand(
                diagnosticsCompensatingStopAction,
                2,
                explicitUserIntentGeneration = first.processGeneration,
                durableReference = first.durable.authority,
            )
            val newer = authority.begin(Mode.Proxy, 300_000, authority.snapshotAuthority())
            hold.complete(Unit)
            runCurrent()
            assertEquals(0, stops)
            assertEquals(newer, authority.snapshot())
            shell.close()
        }
}
