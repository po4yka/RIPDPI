package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DefaultServiceStateStore
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.boot.BootSessionPointer
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

class ProcessDeathResumeCoordinatorTest {
    @Test
    fun `durable running session resumes once and publishes reconnecting`() =
        runTest {
            val store = FakeProcessDeathBootStore(running = true)
            val state = DefaultServiceStateStore()
            val controller = RecordingProcessDeathServiceController()
            val coordinator = coordinator(store, state, controller)

            coordinator.resumeIfNeeded()
            coordinator.resumeIfNeeded()

            assertEquals(listOf(Mode.VPN), controller.startedModes)
            assertEquals(1, controller.processDeathStarts)
            assertEquals(AppStatus.Reconnecting to Mode.VPN, state.status.value)
        }

    @Test
    fun `explicitly stopped session is not resumed`() =
        runTest {
            val store = FakeProcessDeathBootStore(running = false)
            val state = DefaultServiceStateStore()
            val controller = RecordingProcessDeathServiceController()

            coordinator(store, state, controller).resumeIfNeeded()

            assertTrue(controller.startedModes.isEmpty())
            assertEquals(AppStatus.Halted to Mode.VPN, state.status.value)
        }

    @Test
    fun `sticky restart state suppresses fallback resume`() =
        runTest {
            val store = FakeProcessDeathBootStore(running = true)
            val state = DefaultServiceStateStore().apply { setStatus(AppStatus.Reconnecting, Mode.VPN) }
            val controller = RecordingProcessDeathServiceController()

            coordinator(store, state, controller).resumeIfNeeded()

            assertTrue(controller.startedModes.isEmpty())
            assertEquals(AppStatus.Reconnecting to Mode.VPN, state.status.value)
        }

    @Test
    fun `stale profile clears pointer without starting service`() =
        runTest {
            val store = FakeProcessDeathBootStore(running = true)
            val state = DefaultServiceStateStore()
            val controller = RecordingProcessDeathServiceController()

            coordinator(store, state, controller, resumable = false).resumeIfNeeded()

            assertTrue(controller.startedModes.isEmpty())
            assertNull(store.lastSession())
            assertEquals(AppStatus.Halted to Mode.VPN, state.status.value)
        }

    @Test
    fun `rejected restart remains retryable and does not leave status reconnecting`() =
        runTest {
            val store = FakeProcessDeathBootStore(running = true)
            val state = DefaultServiceStateStore()
            val controller =
                RecordingProcessDeathServiceController(
                    result =
                        ServiceStartResult.Rejected(
                            Mode.VPN,
                            ServiceStartRejectionReason.VpnConsentMissing,
                        ),
                )

            val coordinator = coordinator(store, state, controller)
            coordinator.resumeIfNeeded()

            assertEquals(listOf(Mode.VPN), controller.startedModes)
            assertEquals(AppStatus.Halted to Mode.VPN, state.status.value)

            controller.result = ServiceStartResult.Accepted(Mode.VPN)
            coordinator.resumeIfNeeded()

            assertEquals(listOf(Mode.VPN, Mode.VPN), controller.startedModes)
            assertEquals(AppStatus.Reconnecting to Mode.VPN, state.status.value)
        }

    @Test
    fun `explicit stop while profile guard is suspended suppresses resume`() =
        runTest {
            val store = FakeProcessDeathBootStore(running = true)
            val state = DefaultServiceStateStore()
            val controller = RecordingProcessDeathServiceController()
            val arbiter = ServiceIntentArbiter()
            val guardEntered = CompletableDeferred<Unit>()
            val releaseGuard = CompletableDeferred<Unit>()
            val stopOwnsArbiter = CountDownLatch(1)
            val releaseStop = CountDownLatch(1)
            val coordinator =
                coordinator(store, state, controller, arbiter = arbiter) {
                    guardEntered.complete(Unit)
                    releaseGuard.await()
                    true
                }

            val resume = async(Dispatchers.Default) { coordinator.resumeIfNeeded() }
            guardEntered.await()
            val stop =
                async(Dispatchers.Default) {
                    arbiter.serialize {
                        store.setWasRunningAtUpdate(false)
                        stopOwnsArbiter.countDown()
                        releaseStop.await()
                    }
                }
            stopOwnsArbiter.await()
            releaseGuard.complete(Unit)
            releaseStop.countDown()
            stop.await()
            resume.await()

            assertTrue(controller.startedModes.isEmpty())
            assertEquals(AppStatus.Halted to Mode.VPN, state.status.value)
        }

    @Test
    fun `sticky restart while profile guard is suspended suppresses duplicate start`() =
        runTest {
            val store = FakeProcessDeathBootStore(running = true)
            val state = DefaultServiceStateStore()
            val controller = RecordingProcessDeathServiceController()
            val guardEntered = CompletableDeferred<Unit>()
            val releaseGuard = CompletableDeferred<Unit>()
            val coordinator =
                coordinator(store, state, controller) {
                    guardEntered.complete(Unit)
                    releaseGuard.await()
                    true
                }

            val resume = async { coordinator.resumeIfNeeded() }
            guardEntered.await()
            state.setStatus(AppStatus.Reconnecting, Mode.VPN)
            releaseGuard.complete(Unit)
            resume.await()

            assertTrue(controller.startedModes.isEmpty())
            assertEquals(AppStatus.Reconnecting to Mode.VPN, state.status.value)
        }

    @Test
    fun `accepted explicit start suppresses stale recovery while status is still halted`() =
        runTest {
            val store = FakeProcessDeathBootStore(running = true)
            val state = DefaultServiceStateStore()
            val controller = RecordingProcessDeathServiceController()
            val arbiter = ServiceIntentArbiter()
            val coordinator = coordinator(store, state, controller, arbiter = arbiter)

            arbiter.userStart(
                action = { ServiceStartResult.Accepted(Mode.Proxy) },
                isAccepted = { true },
            )
            coordinator.resumeIfNeeded()

            assertTrue(controller.startedModes.isEmpty())
            assertEquals(AppStatus.Halted to Mode.VPN, state.status.value)
        }

    @Test
    fun `stale guard result does not clear a replaced session pointer`() =
        runTest {
            val store = FakeProcessDeathBootStore(running = true)
            val state = DefaultServiceStateStore()
            val controller = RecordingProcessDeathServiceController()
            val guardEntered = CompletableDeferred<Unit>()
            val releaseGuard = CompletableDeferred<Unit>()
            val coordinator =
                coordinator(store, state, controller) {
                    guardEntered.complete(Unit)
                    releaseGuard.await()
                    false
                }

            val resume = async { coordinator.resumeIfNeeded() }
            guardEntered.await()
            val replacement = BootSessionPointer("replacement", Mode.Proxy)
            store.recordSession(replacement.profileId, replacement.mode)
            releaseGuard.complete(Unit)
            resume.await()

            assertEquals(replacement, store.lastSession())
            assertTrue(controller.startedModes.isEmpty())
        }

    @Test
    fun `cancelled activity attempt remains retryable`() =
        runTest {
            val store = FakeProcessDeathBootStore(running = true)
            val state = DefaultServiceStateStore()
            val controller = RecordingProcessDeathServiceController()
            val guardEntered = CompletableDeferred<Unit>()
            var guardAttempts = 0
            val coordinator =
                coordinator(store, state, controller) {
                    guardAttempts += 1
                    if (guardAttempts == 1) {
                        guardEntered.complete(Unit)
                        awaitCancellation()
                    }
                    true
                }

            val firstAttempt = launch { coordinator.resumeIfNeeded() }
            guardEntered.await()
            firstAttempt.cancelAndJoin()
            coordinator.resumeIfNeeded()

            assertEquals(2, guardAttempts)
            assertEquals(listOf(Mode.VPN), controller.startedModes)
            assertEquals(AppStatus.Reconnecting to Mode.VPN, state.status.value)
        }

    @Test
    fun `failed profile lookup remains retryable`() =
        runTest {
            val store = FakeProcessDeathBootStore(running = true)
            val state = DefaultServiceStateStore()
            val controller = RecordingProcessDeathServiceController()
            var guardAttempts = 0
            val coordinator =
                coordinator(store, state, controller) {
                    guardAttempts += 1
                    check(guardAttempts > 1) { "transient profile lookup failure" }
                    true
                }

            val firstFailure = runCatching { coordinator.resumeIfNeeded() }.exceptionOrNull()
            coordinator.resumeIfNeeded()

            assertTrue(firstFailure is IllegalStateException)
            assertEquals(2, guardAttempts)
            assertEquals(listOf(Mode.VPN), controller.startedModes)
            assertEquals(AppStatus.Reconnecting to Mode.VPN, state.status.value)
        }

    private fun coordinator(
        store: BootSessionStateStore,
        state: DefaultServiceStateStore,
        controller: ServiceController,
        resumable: Boolean = true,
        arbiter: ServiceIntentArbiter = ServiceIntentArbiter(),
        profileGuard: (suspend (String) -> Boolean)? = null,
    ): ProcessDeathResumeCoordinator =
        ProcessDeathResumeCoordinator(
            bootSessionStateStore = store,
            profileGuard =
                object : BootResumeProfileGuard {
                    override suspend fun isResumable(profileId: String): Boolean =
                        profileGuard?.invoke(profileId) ?: resumable
                },
            serviceController = controller,
            serviceStateStore = state,
            serviceIntentArbiter = arbiter,
        )
}

private class FakeProcessDeathBootStore(
    private var pointer: BootSessionPointer? = BootSessionPointer("default", Mode.VPN),
    private var running: Boolean,
) : BootSessionStateStore {
    override fun lastSession(): BootSessionPointer? = pointer

    override fun recordSession(
        profileId: String,
        mode: Mode,
    ) {
        pointer = BootSessionPointer(profileId, mode)
    }

    override fun clear() {
        pointer = null
    }

    override fun wasRunningAtUpdate(): Boolean = running

    override fun setWasRunningAtUpdate(value: Boolean) {
        running = value
    }
}

private class RecordingProcessDeathServiceController(
    var result: ServiceStartResult = ServiceStartResult.Accepted(Mode.VPN),
) : ServiceController {
    val startedModes = mutableListOf<Mode>()
    var processDeathStarts = 0
        private set

    override fun start(mode: Mode): ServiceStartResult {
        startedModes += mode
        return result
    }

    override fun startForProcessDeathRecovery(mode: Mode): ServiceStartResult {
        processDeathStarts += 1
        return start(mode)
    }

    override fun stop() = Unit
}
