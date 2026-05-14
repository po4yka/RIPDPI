package com.poyka.ripdpi.services

import com.poyka.ripdpi.services.selector.SelectorReloadCoordinator
import com.poyka.ripdpi.services.selector.SelectorReloadTrigger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SelectorReloadCoordinator]: watches the selected-member
 * signal of a selector group and drives a hot reload of the running relay
 * supervisor when the selection changes — no full service tear-down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectorReloadCoordinatorTest {
    /** Records hot-reload invocations so tests can assert on them. */
    private class RecordingReloadTrigger : SelectorReloadTrigger {
        val reloadedProfileIds = mutableListOf<String>()
        var teardownCount = 0

        override suspend fun hotReload(profileId: String) {
            reloadedProfileIds += profileId
        }

        override suspend fun teardown() {
            teardownCount += 1
        }
    }

    @Test
    fun `changing the selected profile while running triggers a hot reload`() =
        runTest {
            val selection = MutableStateFlow<String?>("profile-1")
            val trigger = RecordingReloadTrigger()
            val coordinator =
                SelectorReloadCoordinator(
                    scope = backgroundScope,
                    selectedProfileId = selection,
                    trigger = trigger,
                )

            coordinator.start()
            runCurrent()

            selection.value = "profile-2"
            runCurrent()

            assertEquals(listOf("profile-2"), trigger.reloadedProfileIds)
            // A hot reload never tears the service down.
            assertEquals(0, trigger.teardownCount)
        }

    @Test
    fun `the initial selection does not trigger a reload`() =
        runTest {
            val selection = MutableStateFlow<String?>("profile-1")
            val trigger = RecordingReloadTrigger()
            val coordinator =
                SelectorReloadCoordinator(
                    scope = backgroundScope,
                    selectedProfileId = selection,
                    trigger = trigger,
                )

            coordinator.start()
            runCurrent()

            // No change yet — the coordinator must not reload on the seed value.
            assertTrue(trigger.reloadedProfileIds.isEmpty())
        }

    @Test
    fun `consecutive selection changes each trigger their own hot reload`() =
        runTest {
            val selection = MutableStateFlow<String?>("profile-1")
            val trigger = RecordingReloadTrigger()
            val coordinator =
                SelectorReloadCoordinator(
                    scope = backgroundScope,
                    selectedProfileId = selection,
                    trigger = trigger,
                )

            coordinator.start()
            runCurrent()

            selection.value = "profile-2"
            runCurrent()
            selection.value = "profile-3"
            runCurrent()

            assertEquals(listOf("profile-2", "profile-3"), trigger.reloadedProfileIds)
        }

    @Test
    fun `re-selecting the same profile does not trigger a redundant reload`() =
        runTest {
            val selection = MutableStateFlow<String?>("profile-1")
            val trigger = RecordingReloadTrigger()
            val coordinator =
                SelectorReloadCoordinator(
                    scope = backgroundScope,
                    selectedProfileId = selection,
                    trigger = trigger,
                )

            coordinator.start()
            runCurrent()

            selection.value = "profile-2"
            runCurrent()
            // Emitting the same value again must be deduplicated.
            selection.value = "profile-2"
            runCurrent()

            assertEquals(listOf("profile-2"), trigger.reloadedProfileIds)
        }

    @Test
    fun `a null selection clears without triggering a reload`() =
        runTest {
            val selection = MutableStateFlow<String?>("profile-1")
            val trigger = RecordingReloadTrigger()
            val coordinator =
                SelectorReloadCoordinator(
                    scope = backgroundScope,
                    selectedProfileId = selection,
                    trigger = trigger,
                )

            coordinator.start()
            runCurrent()

            selection.value = null
            runCurrent()

            assertTrue(trigger.reloadedProfileIds.isEmpty())
        }

    @Test
    fun `stop halts further reload propagation`() =
        runTest {
            val selection = MutableStateFlow<String?>("profile-1")
            val trigger = RecordingReloadTrigger()
            val coordinator =
                SelectorReloadCoordinator(
                    scope = backgroundScope,
                    selectedProfileId = selection,
                    trigger = trigger,
                )

            coordinator.start()
            runCurrent()
            coordinator.stop()
            runCurrent()

            selection.value = "profile-2"
            runCurrent()

            assertTrue(trigger.reloadedProfileIds.isEmpty())
        }
}
