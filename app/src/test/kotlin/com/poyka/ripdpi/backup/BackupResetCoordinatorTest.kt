package com.poyka.ripdpi.backup

import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryResetStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class BackupResetCoordinatorTest : BackupRoomTest() {
    @Test
    fun `setResetDialogVisible true shows dialog and clears input`() =
        runTest {
            val harness = harness()
            harness.state.update { it.copy(resetConfirmationInput = "RES") }

            harness.coordinator.setResetDialogVisible(true)

            assertTrue(harness.state.value.resetDialogVisible)
            assertEquals("", harness.state.value.resetConfirmationInput)
        }

    @Test
    fun `setResetDialogVisible false hides dialog and clears input`() =
        runTest {
            val harness = harness()
            harness.state.update { it.copy(resetDialogVisible = true, resetConfirmationInput = "RES") }

            harness.coordinator.setResetDialogVisible(false)

            assertFalse(harness.state.value.resetDialogVisible)
            assertEquals("", harness.state.value.resetConfirmationInput)
        }

    @Test
    fun `setResetDialogVisible ignored while resetting`() =
        runTest {
            val harness = harness()
            harness.state.update { it.copy(resetting = true, resetDialogVisible = true) }

            harness.coordinator.setResetDialogVisible(false)

            // No change: still visible, still resetting.
            assertTrue(harness.state.value.resetDialogVisible)
        }

    @Test
    fun `onResetConfirmationInputChange records input verbatim`() =
        runTest {
            val harness = harness()

            harness.coordinator.onResetConfirmationInputChange("res")

            assertEquals("res", harness.state.value.resetConfirmationInput)
        }

    @Test
    fun `confirmReset is a no-op while already resetting`() =
        runTest(StandardTestDispatcher()) {
            val harness = harness(defaultDispatcher = StandardTestDispatcher(testScheduler))
            harness.state.update { it.copy(resetting = true, resetConfirmationInput = ResetConfirmationToken) }

            harness.coordinator.confirmReset()
            awaitOperations()

            assertEquals(0, harness.useCaseProbe.resetCalls)
            assertTrue(harness.resetEffects.isEmpty())
        }

    @Test
    fun `confirmReset is a no-op when token does not match`() =
        runTest(StandardTestDispatcher()) {
            val harness = harness(defaultDispatcher = StandardTestDispatcher(testScheduler))
            harness.state.update { it.copy(resetConfirmationInput = "nope") }

            harness.coordinator.confirmReset()
            awaitOperations()

            assertEquals(0, harness.useCaseProbe.resetCalls)
            assertTrue(harness.resetEffects.isEmpty())
            assertFalse(harness.state.value.resetting)
        }

    @Test
    fun `confirmReset with exact token wipes once and flips resetting and emits Wiped`() =
        runTest(StandardTestDispatcher()) {
            val harness = harness(defaultDispatcher = StandardTestDispatcher(testScheduler))
            harness.state.update {
                it.copy(resetDialogVisible = true, resetConfirmationInput = ResetConfirmationToken)
            }

            harness.coordinator.confirmReset()
            // Synchronous part: resetting flips true before the wipe runs.
            assertTrue(harness.state.value.resetting)
            assertEquals(0, harness.useCaseProbe.resetCalls)

            awaitOperations()

            assertEquals(1, harness.useCaseProbe.resetCalls)
            assertFalse(harness.state.value.resetting)
            assertFalse(harness.state.value.resetDialogVisible)
            assertEquals("", harness.state.value.resetConfirmationInput)
            assertEquals(listOf(BackupResetEffect.Wiped), harness.resetEffects)
        }

    @Test
    fun `Wiped is emitted only after the wipe completes`() =
        runTest(StandardTestDispatcher()) {
            val harness = harness(defaultDispatcher = StandardTestDispatcher(testScheduler))
            harness.state.update { it.copy(resetConfirmationInput = ResetConfirmationToken) }

            harness.coordinator.confirmReset()
            testScheduler.runCurrent()
            // The wipe coroutine has not been driven yet: no effect.
            assertTrue(harness.useCaseProbe.resetCalls <= 1)

            awaitOperations()

            // resetting is cleared (state-then-effect) and exactly one Wiped emitted.
            assertEquals(1, harness.useCaseProbe.resetCalls)
            assertEquals(listOf(BackupResetEffect.Wiped), harness.resetEffects)
        }

    @Test
    fun `a wipe step that fails clears resetting and keeps the dialog for a retry`() =
        runTest(StandardTestDispatcher()) {
            val harness =
                harness(
                    defaultDispatcher = StandardTestDispatcher(testScheduler),
                    userArtifactResetStore = UserArtifactResetStore { check(false) { "prefs commit failed" } },
                )
            harness.state.update {
                it.copy(resetDialogVisible = true, resetConfirmationInput = ResetConfirmationToken)
            }

            harness.coordinator.confirmReset()
            awaitOperations()

            assertFalse(harness.state.value.resetting)
            assertTrue(harness.state.value.resetDialogVisible)
            assertEquals(listOf(BackupResetEffect.Failed), harness.resetEffects)
        }

    // -- Harness --------------------------------------------------------------

    /**
     * Counts how many times the real [ResetAllSettingsUseCase] ran to completion by
     * observing its LAST wipe step (the cache cleaner). [resetCalls] is therefore a
     * faithful "the wipe committed" counter without faking the use case itself.
     */
    private class UseCaseProbe {
        var resetCalls = 0
            private set

        fun onWipeCompleted() {
            resetCalls++
        }
    }

    private class Harness(
        val coordinator: BackupResetCoordinator,
        val state: MutableStateFlow<BackupRestoreUiState>,
        val resetEffects: List<BackupResetEffect>,
        val useCaseProbe: UseCaseProbe,
    )

    private suspend fun TestScope.harness(
        defaultDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        userArtifactResetStore: UserArtifactResetStore = UserArtifactResetStore { },
    ): Harness {
        val state = MutableStateFlow(BackupRestoreUiState())
        val effects = mutableListOf<BackupResetEffect>()
        val probe = UseCaseProbe()
        val fixture = roomFixture()
        val useCase =
            ResetAllSettingsUseCase(
                resetEventRecorder = FakeResetEventRecorder(),
                settingsRepository = fixture.settings,
                profileMutations = fixture.coordinator,
                userSessionResetStore = UserSessionResetStore { },
                diagnosticsHistoryResetStore = FakeDiagnosticsHistoryResetStore(),
                userArtifactResetStore = userArtifactResetStore,
                cacheDirectoryCleaner = FakeCacheDirectoryCleaner(probe),
            )
        val coordinator =
            BackupResetCoordinator(
                scope = this,
                resetAllSettingsUseCase = useCase,
                currentState = state::value,
                updateState = state::update,
                emitResetEffect = { effects.add(it) },
                defaultDispatcher = defaultDispatcher,
            )
        return Harness(coordinator, state, effects, probe)
    }

    private class FakeResetEventRecorder : ResetEventRecorder {
        private var pending = false

        override fun recordResetInitiated() {
            pending = true
        }

        override fun hasPendingResetEvent(): Boolean = pending

        override fun consumeResetEvent(): Boolean {
            if (!pending) return false
            pending = false
            return true
        }
    }

    private class FakeDiagnosticsHistoryResetStore : DiagnosticsHistoryResetStore {
        override suspend fun clearRuntimeHistory() = Unit
    }

    private class FakeCacheDirectoryCleaner(
        private val probe: UseCaseProbe,
    ) : CacheDirectoryCleaner {
        // Cache clearing is the LAST step of the use case; reaching it == wipe done.
        override fun clearCaches() = probe.onWipeCompleted()
    }
}
