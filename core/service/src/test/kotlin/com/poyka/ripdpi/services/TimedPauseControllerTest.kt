package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.PauseAuthorityPersistence
import com.poyka.ripdpi.data.PauseAuthorityState
import com.poyka.ripdpi.data.PauseClock
import com.poyka.ripdpi.data.PauseClockReading
import com.poyka.ripdpi.data.PauseIntentAuthority
import com.poyka.ripdpi.data.PausePhase
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimedPauseControllerTest {
    @Test fun `failed checked pause persistence keeps original runtime and never releases resources`() =
        runTest {
            var fail = false
            var disk: PauseAuthorityState? = null
            val authority =
                PauseIntentAuthority(
                    object : PauseAuthorityPersistence {
                        override fun read() = disk

                        override fun commit(state: PauseAuthorityState) {
                            check(!fail)
                            disk = state
                        }
                    },
                    object : PauseClock {
                        override fun read() = PauseClockReading(1_800_000_000_000, 10_000, 7)
                    },
                    com.poyka.ripdpi.data
                        .RuntimeIntentLinearizer(),
                )
            authority.initializeAfterMigration()
            val state = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy)
            val controller =
                TimedPauseController(
                    RuntimeEnvironment.getApplication(),
                    authority,
                    com.poyka.ripdpi.data
                        .testProfileRecovery(),
                    state,
                    LiveVpnLockdownReader(),
                    ServiceIntentArbiter(authority),
                )
            var released = 0
            controller.attach(
                PausedServiceHost(
                    Any(),
                    Mode.Proxy,
                    backgroundScope,
                    release = {
                        released++
                        RuntimeStopOutcome.FullyReleased
                    },
                    resume = {},
                    showPaused = {},
                    stopShell = { RuntimeStopOutcome.FullyReleased },
                    discardIdleShell = {},
                ),
            )
            fail = true
            assertTrue(runCatching { controller.pause(300_000) }.isFailure)
            assertEquals(0, released)
            assertEquals(AppStatus.Running to Mode.Proxy, state.status.value)
            assertNull(authority.snapshot())
        }

    @Test fun `cleanup pending never claims paused or schedules an eligible resume`() =
        runTest {
            val authority =
                com.poyka.ripdpi.data
                    .testPauseAuthority()
            val state = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy)
            val controller =
                TimedPauseController(
                    RuntimeEnvironment.getApplication(),
                    authority,
                    com.poyka.ripdpi.data
                        .testProfileRecovery(),
                    state,
                    LiveVpnLockdownReader(),
                    ServiceIntentArbiter(authority),
                )
            var released = 0
            controller.attach(
                PausedServiceHost(
                    Any(),
                    Mode.Proxy,
                    backgroundScope,
                    release = {
                        released++
                        RuntimeStopOutcome.CleanupPending
                    },
                    resume = {},
                    showPaused = {},
                    stopShell = { RuntimeStopOutcome.FullyReleased },
                    discardIdleShell = {},
                ),
            )
            controller.pause(300_000)
            assertEquals(1, released)
            assertEquals(PausePhase.CleanupPending, authority.snapshot()?.phase)
            assertFalse(authority.claimResume(checkNotNull(authority.snapshot()), true))
        }

    @Test fun `fully released pause retains chosen mode and explicit cancel fences old alarm`() =
        runTest {
            val authority =
                com.poyka.ripdpi.data
                    .testPauseAuthority()
            val state = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy)
            val controller =
                TimedPauseController(
                    RuntimeEnvironment.getApplication(),
                    authority,
                    com.poyka.ripdpi.data
                        .testProfileRecovery(),
                    state,
                    LiveVpnLockdownReader(),
                    ServiceIntentArbiter(authority),
                )
            var released = 0
            var shellStops = 0
            controller.attach(
                PausedServiceHost(
                    Any(),
                    Mode.Proxy,
                    backgroundScope,
                    release = {
                        released++
                        state.setStatus(AppStatus.Halted, Mode.Proxy)
                        RuntimeStopOutcome.FullyReleased
                    },
                    resume = {},
                    showPaused = {},
                    stopShell = {
                        shellStops++
                        RuntimeStopOutcome.FullyReleased
                    },
                    discardIdleShell = {},
                ),
            )
            controller.pause(300_000)
            val paused = authority.snapshot()
            assertNotNull(paused)
            assertEquals(PausePhase.Paused, paused!!.phase)
            assertEquals(Mode.Proxy.preferenceValue, paused.mode)
            assertEquals(1, released)
            controller.stop()
            assertNull(authority.snapshot())
            assertEquals(1, shellStops)
            assertFalse(authority.claimResume(paused, false))
        }

    @Test
    fun `cancel retains cleanup owner and foreground until a successful checked retry`() =
        runTest {
            val authority =
                com.poyka.ripdpi.data
                    .testPauseAuthority()
            val arbiter = ServiceIntentArbiter(authority)
            val state = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy)
            val controller =
                TimedPauseController(
                    RuntimeEnvironment.getApplication(),
                    authority,
                    com.poyka.ripdpi.data
                        .testProfileRecovery(),
                    state,
                    LiveVpnLockdownReader(),
                    arbiter,
                )
            var cleanupPending = true
            var attempts = 0
            var shellStops = 0
            controller.attach(
                PausedServiceHost(
                    Any(),
                    Mode.Proxy,
                    backgroundScope,
                    release = { RuntimeStopOutcome.CleanupPending },
                    resume = {},
                    showPaused = {},
                    stopShell = { lease ->
                        attempts++
                        assertTrue(arbiter.isCurrent(lease))
                        assertNull(authority.snapshot())
                        assertEquals(com.poyka.ripdpi.data.DesiredRuntimeState.Stopped, authority.states.value?.desired)
                        if (cleanupPending) {
                            RuntimeStopOutcome.CleanupPending
                        } else {
                            shellStops++
                            RuntimeStopOutcome.FullyReleased
                        }
                    },
                    discardIdleShell = {},
                ),
            )
            controller.pause(300_000)
            val oldPause = checkNotNull(authority.snapshot())
            assertEquals(RuntimeStopOutcome.CleanupPending, controller.stop())
            assertNull(authority.snapshot())
            assertEquals(PausePhase.CleanupPending, controller.cleanupPending.value?.phase)
            assertFalse(controller.canPause())
            assertFalse(authority.claimResume(oldPause, true))
            assertEquals(0, shellStops)
            cleanupPending = false
            assertEquals(RuntimeStopOutcome.FullyReleased, controller.stop())
            assertNull(controller.cleanupPending.value)
            assertEquals(2, attempts)
            assertEquals(1, shellStops)
        }

    @Test
    fun `older cancel cannot stop a newer pause while cleanup is suspended`() =
        runTest {
            val authority =
                com.poyka.ripdpi.data
                    .testPauseAuthority()
            val arbiter = ServiceIntentArbiter(authority)
            val state = TestServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy)
            val controller =
                TimedPauseController(
                    RuntimeEnvironment.getApplication(),
                    authority,
                    com.poyka.ripdpi.data
                        .testProfileRecovery(),
                    state,
                    LiveVpnLockdownReader(),
                    arbiter,
                )
            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            var shellStops = 0
            controller.attach(
                PausedServiceHost(
                    Any(),
                    Mode.Proxy,
                    backgroundScope,
                    release = { RuntimeStopOutcome.CleanupPending },
                    resume = {},
                    showPaused = {},
                    stopShell = { lease ->
                        started.complete(Unit)
                        release.await()
                        if (arbiter.isCurrent(lease)) {
                            shellStops++
                            RuntimeStopOutcome.FullyReleased
                        } else {
                            RuntimeStopOutcome.Superseded
                        }
                    },
                    discardIdleShell = {},
                ),
            )
            controller.pause(300_000)
            val stop = async { controller.stop() }
            started.await()
            val newer = authority.begin(Mode.Proxy, 900_000, authority.snapshotAuthority())
            release.complete(Unit)
            assertEquals(RuntimeStopOutcome.Superseded, stop.await())
            assertEquals(newer, authority.snapshot())
            assertEquals(0, shellStops)
            assertNull(controller.cleanupPending.value)
        }

    @Test
    fun `overlapping cancels cannot report release or stop foreground before native cleanup completes`() =
        runTest {
            val authority =
                com.poyka.ripdpi.data
                    .testPauseAuthority()
            val arbiter = ServiceIntentArbiter(authority)
            val controller =
                TimedPauseController(
                    RuntimeEnvironment.getApplication(),
                    authority,
                    com.poyka.ripdpi.data
                        .testProfileRecovery(),
                    TestServiceStateStore(initialStatus = AppStatus.Running to Mode.Proxy),
                    LiveVpnLockdownReader(),
                    arbiter,
                )
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val native = kotlinx.coroutines.sync.Mutex()
            var pending = true
            var shellStops = 0
            controller.attach(
                PausedServiceHost(
                    Any(),
                    Mode.Proxy,
                    backgroundScope,
                    release = { RuntimeStopOutcome.CleanupPending },
                    resume = {},
                    showPaused = {},
                    stopShell = { lease ->
                        native.withLock {
                            entered.complete(Unit)
                            release.await()
                            when {
                                !arbiter.isCurrent(lease) -> {
                                    RuntimeStopOutcome.Superseded
                                }

                                pending -> {
                                    RuntimeStopOutcome.CleanupPending
                                }

                                else -> {
                                    shellStops++
                                    RuntimeStopOutcome.FullyReleased
                                }
                            }
                        }
                    },
                    discardIdleShell = {},
                ),
            )
            controller.pause(300_000)
            val first = async { controller.stop() }
            entered.await()
            val firstGeneration = authority.reference().generation
            val second = async { controller.stop() }
            authority.states.first { it != null && it.generation > firstGeneration }
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertEquals(0, shellStops)
            release.complete(Unit)
            assertEquals(RuntimeStopOutcome.Superseded, first.await())
            assertEquals(RuntimeStopOutcome.CleanupPending, second.await())
            assertEquals(authority.reference().generation, controller.cleanupPending.value?.generation)
            assertEquals(0, shellStops)
            pending = false
            assertEquals(RuntimeStopOutcome.FullyReleased, controller.stop())
            assertEquals(1, shellStops)
        }

    @Test
    fun `token bound notification stop cannot cancel a new pause after suspended recovery`() =
        runTest {
            val authority =
                com.poyka.ripdpi.data
                    .testPauseAuthority()
            val old = authority.begin(Mode.Proxy, 300_000, authority.snapshotAuthority())
            val recovering = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val recovery =
                object : com.poyka.ripdpi.data.ProfileMutationRecoveryAccess by com.poyka.ripdpi.data
                    .testProfileRecovery() {
                    override suspend fun recover() {
                        recovering.complete(Unit)
                        release.await()
                    }

                    override suspend fun <T> readRecovered(block: suspend () -> T): T {
                        recover()
                        return block()
                    }

                    override suspend fun runReset(
                        block: suspend (com.poyka.ripdpi.data.DurableCommandReceipt) -> Unit,
                    ) = error("Not used")
                }
            val controller =
                TimedPauseController(
                    RuntimeEnvironment.getApplication(),
                    authority,
                    recovery,
                    TestServiceStateStore(),
                    LiveVpnLockdownReader(),
                    ServiceIntentArbiter(authority),
                )
            var stops = 0
            controller.attach(
                PausedServiceHost(
                    Any(),
                    Mode.Proxy,
                    backgroundScope,
                    release = { RuntimeStopOutcome.FullyReleased },
                    resume = {},
                    showPaused = {},
                    stopShell = {
                        stops++
                        RuntimeStopOutcome.FullyReleased
                    },
                    discardIdleShell = {},
                ),
            )
            val command =
                org.robolectric.Shadows
                    .shadowOf(
                        TimedPauseController.pendingIntent(
                            RuntimeEnvironment.getApplication(),
                            old,
                            TimedPauseController.PauseStopAction,
                        ),
                    ).savedIntent
            val delivered = async { controller.receive(command) }
            recovering.await()
            val newer = authority.begin(Mode.Proxy, 900_000, authority.snapshotAuthority())
            release.complete(Unit)
            delivered.await()
            assertEquals(newer, authority.snapshot())
            assertEquals(0, stops)
        }

    @Test
    fun `token bound notification resume cannot claim new pause after command wait`() =
        runTest {
            val authority =
                com.poyka.ripdpi.data
                    .testPauseAuthority()
            val old = authority.begin(Mode.Proxy, 300_000, authority.snapshotAuthority())
            check(authority.transition(old, PausePhase.Paused, null))
            val controller =
                TimedPauseController(
                    RuntimeEnvironment.getApplication(),
                    authority,
                    com.poyka.ripdpi.data
                        .testProfileRecovery(),
                    TestServiceStateStore(),
                    LiveVpnLockdownReader(),
                    ServiceIntentArbiter(authority),
                )
            var resumes = 0
            controller.attach(
                PausedServiceHost(
                    Any(),
                    Mode.Proxy,
                    backgroundScope,
                    release = { RuntimeStopOutcome.FullyReleased },
                    resume = { resumes++ },
                    showPaused = {},
                    stopShell = { RuntimeStopOutcome.FullyReleased },
                    discardIdleShell = {},
                ),
            )
            val command =
                org.robolectric.Shadows
                    .shadowOf(
                        TimedPauseController.pendingIntent(
                            RuntimeEnvironment.getApplication(),
                            old,
                            TimedPauseController.PauseResumeAction,
                        ),
                    ).savedIntent
            val mutex =
                org.robolectric.util.ReflectionHelpers.getField<kotlinx.coroutines.sync.Mutex>(
                    controller,
                    "commands",
                )
            mutex.lock()
            val delivered =
                async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { controller.receive(command) }
            assertFalse(delivered.isCompleted)
            val newer = authority.begin(Mode.Proxy, 900_000, authority.snapshotAuthority())
            mutex.unlock()
            delivered.await()
            assertEquals(newer, authority.snapshot())
            assertEquals(0, resumes)
        }

    @Test
    fun `cancel reservation is visible before recovered command return and native cleanup`() =
        runTest {
            val authority =
                com.poyka.ripdpi.data
                    .testPauseAuthority()
            authority.begin(Mode.Proxy, 300_000, authority.snapshotAuthority())
            val arbiter = ServiceIntentArbiter(authority)
            val reserved = kotlinx.coroutines.CompletableDeferred<Unit>()
            val returnFirst = kotlinx.coroutines.CompletableDeferred<Unit>()
            val nativeEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val nativeRelease = kotlinx.coroutines.CompletableDeferred<Unit>()
            val calls =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val recovery =
                object : com.poyka.ripdpi.data.ProfileMutationRecoveryAccess by com.poyka.ripdpi.data
                    .testProfileRecovery() {
                    override suspend fun <T> readRecovered(block: suspend () -> T): T {
                        val result = block()
                        if (calls.getAndIncrement() == 0) {
                            reserved.complete(Unit)
                            returnFirst.await()
                        }
                        return result
                    }
                }
            val controller =
                TimedPauseController(
                    RuntimeEnvironment.getApplication(),
                    authority,
                    recovery,
                    TestServiceStateStore(),
                    LiveVpnLockdownReader(),
                    arbiter,
                )
            controller.attach(
                PausedServiceHost(
                    Any(),
                    Mode.Proxy,
                    backgroundScope,
                    release = { RuntimeStopOutcome.CleanupPending },
                    resume = {},
                    showPaused = {},
                    stopShell = {
                        nativeEntered.complete(Unit)
                        nativeRelease.await()
                        RuntimeStopOutcome.CleanupPending
                    },
                    discardIdleShell = {},
                ),
            )
            val first = async { controller.stop() }
            reserved.await()
            assertEquals(authority.reference().generation, controller.cleanupPending.value?.generation)
            val second = async { controller.stop() }
            nativeEntered.await()
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            returnFirst.complete(Unit)
            assertEquals(RuntimeStopOutcome.Superseded, first.await())
            nativeRelease.complete(Unit)
            assertEquals(RuntimeStopOutcome.CleanupPending, second.await())
        }
}
