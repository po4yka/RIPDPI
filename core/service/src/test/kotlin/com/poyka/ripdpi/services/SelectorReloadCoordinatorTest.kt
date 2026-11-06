package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.services.selector.SelectorReloadCoordinator
import com.poyka.ripdpi.services.selector.SelectorReloadRequest
import com.poyka.ripdpi.services.selector.SelectorReloadTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SelectorReloadCoordinator]: watches the selected-member
 * signal of a selector group and drives a hot reload of the running relay
 * supervisor when the selection changes — no full service tear-down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectorReloadCoordinatorTest {
    private fun automatic(memberId: String) = SelectorReloadRequest("selector", memberId, null)

    /** Records hot-reload invocations so tests can assert on them. */
    private class RecordingReloadTrigger : SelectorReloadTrigger {
        val reloadedProfileIds = mutableListOf<String>()
        var teardownCount = 0

        override suspend fun hotReload(request: SelectorReloadRequest) {
            val profileId = request.memberId
            reloadedProfileIds += profileId
        }

        override suspend fun teardown() {
            teardownCount += 1
        }
    }

    @Test
    fun `stop before first dispatch cancels startup readiness`() =
        runTest {
            val coordinator =
                SelectorReloadCoordinator(backgroundScope, MutableStateFlow(automatic("a")), RecordingReloadTrigger())
            coordinator.start(Mode.VPN)
            coordinator.stop(Mode.VPN)
            runCurrent()
            val failure = runCatching { coordinator.awaitSubscription() }.exceptionOrNull()
            assertTrue(failure is CancellationException)
        }

    @Test
    fun `failed storage observer surfaces failure and explicit preparation resubscribes`() =
        runTest {
            val failure = IllegalStateException("Profile storage unavailable")
            var available = false
            var subscriptions = 0
            val selection = MutableStateFlow<SelectorReloadRequest?>(automatic("a"))
            val changes =
                kotlinx.coroutines.flow.flow {
                    subscriptions++
                    if (!available) throw failure
                    selection.collect { emit(it) }
                }
            val trigger = RecordingReloadTrigger()
            val coordinator = SelectorReloadCoordinator(backgroundScope, changes, trigger)
            coordinator.start(Mode.VPN)
            val first = runCatching { coordinator.awaitSubscription() }.exceptionOrNull()
            assertTrue(first === failure || first?.cause === failure)
            assertEquals(1, subscriptions)
            assertTrue(trigger.reloadedProfileIds.isEmpty())
            available = true
            coordinator.awaitSubscription()
            assertEquals(2, subscriptions)
            selection.value = automatic("b")
            runCurrent()
            assertEquals(listOf("b"), trigger.reloadedProfileIds)
        }

    @Test
    fun `rejected pending seed drains the old watch before retry and retains both owners`() =
        runTest {
            val failure = IllegalStateException("Profile storage unavailable")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var subscriptions = 0
            val selection = MutableStateFlow<SelectorReloadRequest?>(automatic("a"))
            val changes =
                flow {
                    if (subscriptions++ == 0) {
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                        }
                        throw failure
                    }
                    emitAll(selection)
                }
            val trigger = RecordingReloadTrigger()
            val coordinator = SelectorReloadCoordinator(backgroundScope, changes, trigger)
            coordinator.start(Mode.VPN)
            coordinator.start(Mode.Proxy)
            try {
                entered.await()
                coordinator.rejectPendingSeed(failure)
                val retry = async { coordinator.awaitSubscription() }
                runCurrent()
                assertFalse(retry.isCompleted)
                release.complete(Unit)
                retry.await()
                assertTrue(trigger.reloadedProfileIds.isEmpty())

                // A later rejection cannot retire a seed already used by another owner.
                coordinator.rejectPendingSeed(failure)
                coordinator.stop(Mode.VPN)
                selection.value = automatic("b")
                runCurrent()
                assertEquals(listOf("b"), trigger.reloadedProfileIds)
                coordinator.stop(Mode.Proxy)
                selection.value = automatic("c")
                runCurrent()
                assertEquals(listOf("b"), trigger.reloadedProfileIds)
            } finally {
                release.complete(Unit)
            }
        }

    @Test
    fun `old service destruction retains the new mode owner`() =
        runTest {
            val selection = MutableStateFlow<SelectorReloadRequest?>(automatic("a"))
            val trigger = RecordingReloadTrigger()
            val coordinator = SelectorReloadCoordinator(backgroundScope, selection, trigger)
            coordinator.start(Mode.VPN)
            runCurrent()
            coordinator.start(Mode.Proxy)
            coordinator.start(Mode.Proxy)
            coordinator.stop(Mode.VPN)
            selection.value = automatic("b")
            runCurrent()
            assertEquals(listOf("b"), trigger.reloadedProfileIds)
            coordinator.stop(Mode.Proxy)
            selection.value = automatic("c")
            runCurrent()
            assertEquals(listOf("b"), trigger.reloadedProfileIds)
        }

    @Test
    fun `internal reload timeout does not lose later selection changes`() =
        runTest {
            val selection = MutableStateFlow<SelectorReloadRequest?>(automatic("a"))
            val calls = mutableListOf<String>()
            val trigger =
                object : SelectorReloadTrigger {
                    override suspend fun hotReload(request: SelectorReloadRequest) {
                        val profileId = request.memberId
                        calls += profileId
                        if (profileId == "b") withTimeout(100L) { awaitCancellation() }
                    }

                    override suspend fun teardown() = Unit
                }
            val coordinator = SelectorReloadCoordinator(backgroundScope, selection, trigger)
            coordinator.start(Mode.VPN)
            runCurrent()
            selection.value = automatic("b")
            runCurrent()
            advanceTimeBy(100L)
            runCurrent()
            coordinator.start(Mode.VPN)
            selection.value = automatic("c")
            runCurrent()
            assertEquals(listOf("b", "c"), calls)
        }

    @Test
    fun `changing the selected profile while running triggers a hot reload`() =
        runTest {
            val selection = MutableStateFlow<SelectorReloadRequest?>(automatic("profile-1"))
            val trigger = RecordingReloadTrigger()
            val coordinator =
                SelectorReloadCoordinator(
                    scope = backgroundScope,
                    selectionChanges = selection,
                    trigger = trigger,
                )

            coordinator.start(Mode.VPN)
            runCurrent()

            selection.value = automatic("profile-2")
            runCurrent()

            assertEquals(listOf("profile-2"), trigger.reloadedProfileIds)
            // A hot reload never tears the service down.
            assertEquals(0, trigger.teardownCount)
        }

    @Test
    fun `the initial selection does not trigger a reload`() =
        runTest {
            val selection = MutableStateFlow<SelectorReloadRequest?>(automatic("profile-1"))
            val trigger = RecordingReloadTrigger()
            val coordinator =
                SelectorReloadCoordinator(
                    scope = backgroundScope,
                    selectionChanges = selection,
                    trigger = trigger,
                )

            coordinator.start(Mode.VPN)
            runCurrent()

            // No change yet — the coordinator must not reload on the seed value.
            assertTrue(trigger.reloadedProfileIds.isEmpty())
        }

    @Test
    fun `consecutive selection changes each trigger their own hot reload`() =
        runTest {
            val selection = MutableStateFlow<SelectorReloadRequest?>(automatic("profile-1"))
            val trigger = RecordingReloadTrigger()
            val coordinator =
                SelectorReloadCoordinator(
                    scope = backgroundScope,
                    selectionChanges = selection,
                    trigger = trigger,
                )

            coordinator.start(Mode.VPN)
            runCurrent()

            selection.value = automatic("profile-2")
            runCurrent()
            selection.value = automatic("profile-3")
            runCurrent()

            assertEquals(listOf("profile-2", "profile-3"), trigger.reloadedProfileIds)
        }

    @Test
    fun `re-selecting the same profile does not trigger a redundant reload`() =
        runTest {
            val selection = MutableStateFlow<SelectorReloadRequest?>(automatic("profile-1"))
            val trigger = RecordingReloadTrigger()
            val coordinator =
                SelectorReloadCoordinator(
                    scope = backgroundScope,
                    selectionChanges = selection,
                    trigger = trigger,
                )

            coordinator.start(Mode.VPN)
            runCurrent()

            selection.value = automatic("profile-2")
            runCurrent()
            // Emitting the same value again must be deduplicated.
            selection.value = automatic("profile-2")
            runCurrent()

            assertEquals(listOf("profile-2"), trigger.reloadedProfileIds)
        }

    @Test
    fun `a null selection clears without triggering a reload`() =
        runTest {
            val selection = MutableStateFlow<SelectorReloadRequest?>(automatic("profile-1"))
            val trigger = RecordingReloadTrigger()
            val coordinator =
                SelectorReloadCoordinator(
                    scope = backgroundScope,
                    selectionChanges = selection,
                    trigger = trigger,
                )

            coordinator.start(Mode.VPN)
            runCurrent()

            selection.value = null
            runCurrent()

            assertTrue(trigger.reloadedProfileIds.isEmpty())
        }

    @Test
    fun `stop halts further reload propagation`() =
        runTest {
            val selection = MutableStateFlow<SelectorReloadRequest?>(automatic("profile-1"))
            val trigger = RecordingReloadTrigger()
            val coordinator =
                SelectorReloadCoordinator(
                    scope = backgroundScope,
                    selectionChanges = selection,
                    trigger = trigger,
                )

            coordinator.start(Mode.VPN)
            runCurrent()
            coordinator.stop(Mode.VPN)
            runCurrent()

            selection.value = automatic("profile-2")
            runCurrent()

            assertTrue(trigger.reloadedProfileIds.isEmpty())
        }
}
