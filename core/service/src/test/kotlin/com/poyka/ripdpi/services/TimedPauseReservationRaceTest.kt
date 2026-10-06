package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileMutationRecoveryAccess
import com.poyka.ripdpi.data.RuntimeUserCommand
import com.poyka.ripdpi.data.testPauseAuthority
import com.poyka.ripdpi.data.testProfileRecovery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimedPauseReservationRaceTest {
    @Test
    fun `pause queued behind checked Start cannot adopt its newer authority`() =
        runTest {
            val authority = testPauseAuthority()
            val arbiter = ServiceIntentArbiter(authority)
            val mutex = Mutex()
            val startEntered = CompletableDeferred<Unit>()
            val commitStart = CompletableDeferred<Unit>()
            val pauseQueued = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val recovery =
                object : ProfileMutationRecoveryAccess by testProfileRecovery() {
                    override suspend fun <T> readRecovered(block: suspend () -> T): T {
                        if (calls.incrementAndGet() == 2) pauseQueued.complete(Unit)
                        return mutex.withLock { block() }
                    }
                }
            val state = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy)
            var teardowns = 0
            val controller =
                TimedPauseController(
                    RuntimeEnvironment.getApplication(),
                    authority,
                    recovery,
                    state,
                    LiveVpnLockdownReader(),
                    arbiter,
                )
            controller.attach(
                PausedServiceHost(
                    Any(),
                    Mode.Proxy,
                    backgroundScope,
                    release = {
                        teardowns++
                        RuntimeStopOutcome.FullyReleased
                    },
                    resume = {},
                    showPaused = {},
                    stopShell = { RuntimeStopOutcome.FullyReleased },
                    discardIdleShell = {},
                ),
            )
            val start =
                async {
                    recovery
                        .readRecovered {
                            startEntered.complete(Unit)
                            commitStart.await()
                            authority.supersede(RuntimeUserCommand.Start(Mode.Proxy))
                        }.let { checkNotNull(arbiter.dispatchExplicit(it)) }
                }
            startEntered.await()
            val olderPause = async { runCatching { controller.pause(300_000L) } }
            pauseQueued.await()
            commitStart.complete(Unit)
            val newer = start.await()
            olderPause.await()

            assertTrue(arbiter.isCurrent(newer))
            assertEquals(newer.durable.authority, authority.reference())
            assertNull(authority.snapshot())
            assertEquals(0, teardowns)
            assertEquals(AppStatus.Running to Mode.Proxy, state.status.value)
        }

    @Test
    fun `older pause return cannot advance process intent after newer Start dispatch`() =
        runTest {
            val authority = testPauseAuthority()
            val arbiter = ServiceIntentArbiter(authority)
            val mutex = Mutex()
            val reserved = CompletableDeferred<Unit>()
            val returnPause = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val recovery =
                object : ProfileMutationRecoveryAccess by testProfileRecovery() {
                    override suspend fun <T> readRecovered(block: suspend () -> T): T {
                        val result = mutex.withLock { block() }
                        if (calls.getAndIncrement() == 0) {
                            reserved.complete(Unit)
                            returnPause.await()
                        }
                        return result
                    }
                }
            val state = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy)
            var teardowns = 0
            val controller =
                TimedPauseController(
                    RuntimeEnvironment.getApplication(),
                    authority,
                    recovery,
                    state,
                    LiveVpnLockdownReader(),
                    arbiter,
                )
            controller.attach(
                PausedServiceHost(
                    Any(),
                    Mode.Proxy,
                    backgroundScope,
                    release = { intent ->
                        if (authority.isCurrent(intent)) {
                            teardowns++
                            RuntimeStopOutcome.FullyReleased
                        } else {
                            RuntimeStopOutcome.Superseded
                        }
                    },
                    resume = {},
                    showPaused = {},
                    stopShell = { RuntimeStopOutcome.FullyReleased },
                    discardIdleShell = {},
                ),
            )
            val olderPause = async { controller.pause(300_000L) }
            reserved.await()
            val receipt = recovery.readRecovered { authority.supersede(RuntimeUserCommand.Start(Mode.Proxy)) }
            val newer = checkNotNull(arbiter.dispatchExplicit(receipt))
            returnPause.complete(Unit)
            olderPause.await()

            assertTrue(arbiter.isCurrent(newer))
            assertEquals(newer.durable.authority, authority.reference())
            assertNull(authority.snapshot())
            assertEquals(0, teardowns)
            assertEquals(AppStatus.Running to Mode.Proxy, state.status.value)
        }
}
