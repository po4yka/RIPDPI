package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.startAction
import com.poyka.ripdpi.data.stopAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceShellDelegateCancellationTest {
    @Test
    fun `accepted user stop cancels an in-flight start before teardown`() =
        runTest {
            val authority =
                com.poyka.ripdpi.data
                    .testPauseAuthority()
            val arbiter = ServiceIntentArbiter(authority)
            val neverCompletes = CompletableDeferred<Unit>()
            var startCancelled = false
            val operations = mutableListOf<String>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter =
                    arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "proxy",
                    onStart = {
                        operations += "start-begin"
                        try {
                            neverCompletes.await()
                            operations += "start-end"
                        } finally {
                            startCancelled = true
                        }
                    },
                    onStop = { _, _ -> operations += "stop" },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(authority::reference),
                )

            TestCapturedServiceCommand.start(authority, arbiter, Mode.Proxy).deliver(delegate, startAction, 1)
            runCurrent()

            assertEquals(listOf("start-begin"), operations)
            TestCapturedServiceCommand.stop(authority, arbiter).deliver(delegate, stopAction, 2)
            runCurrent()

            assertTrue(startCancelled)
            assertEquals(listOf("start-begin", "stop"), operations)
        }

    @Test
    fun `accepted user stop drops older starts but preserves a newer start`() =
        runTest {
            val authority =
                com.poyka.ripdpi.data
                    .testPauseAuthority()
            val arbiter = ServiceIntentArbiter(authority)
            val neverCompletes = CompletableDeferred<Unit>()
            val operations = mutableListOf<String>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter =
                    arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "proxy",
                    onStart = {},
                    onStartWithId = { _, startId ->
                        operations += "start-$startId"
                        if (startId == 1) neverCompletes.await()
                    },
                    onStop = { _, _ -> operations += "stop" },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(authority::reference),
                )

            TestCapturedServiceCommand.start(authority, arbiter, Mode.Proxy).deliver(delegate, startAction, 1)
            runCurrent()
            TestCapturedServiceCommand.start(authority, arbiter, Mode.Proxy).deliver(delegate, startAction, 2)
            TestCapturedServiceCommand.stop(authority, arbiter).deliver(delegate, stopAction, 3)
            runCurrent()
            TestCapturedServiceCommand.start(authority, arbiter, Mode.Proxy).deliver(delegate, startAction, 4)
            runCurrent()

            assertEquals(listOf("start-1", "stop", "start-4"), operations)
        }

    @Test
    fun `accepted user stop cancels and rejects an active failover restart`() =
        runTest {
            val authority =
                com.poyka.ripdpi.data
                    .testPauseAuthority()
            val arbiter = ServiceIntentArbiter(authority)
            val neverCompletes = CompletableDeferred<Unit>()
            val operations = mutableListOf<String>()
            val rejectedRequests = mutableListOf<Long>()
            val delegate =
                ServiceShellDelegate(
                    serviceIntentArbiter =
                    arbiter,
                    serviceScope = backgroundScope,
                    serviceLabel = "vpn",
                    onStart = {},
                    onStop = { _, _ -> operations += "stop" },
                    transportFailoverCommandHandler =
                        TransportFailoverCommandHandler(
                            restart = { _, _ ->
                                operations += "failover"
                                neverCompletes.await()
                            },
                            reject = rejectedRequests::add,
                        ),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    intentCallbacks =
                        testShellIntentCallbacks(authority::reference),
                )

            delegate.onStartCommand(
                transportFailoverRestartAction,
                1,
                transportFailoverRequestId = 17L,
                transportFailoverTarget = TransportFailoverTarget(RelayKindVlessReality, "reality-1"),
                durableReference = authority.reference(),
                activation = null,
                stopSnapshot = authority.snapshotAuthority(),
            )
            runCurrent()
            TestCapturedServiceCommand.stop(authority, arbiter).deliver(delegate, stopAction, 2)
            runCurrent()

            assertEquals(listOf("failover", "stop"), operations)
            assertEquals(listOf(17L), rejectedRequests)
        }
}
