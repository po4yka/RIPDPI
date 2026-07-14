package com.poyka.ripdpi.backup

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryResetStore
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RuleDao
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.testsupport.NoOpProfileMutationCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupResetCoordinatorTest {
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
            testScheduler.advanceUntilIdle()

            assertEquals(0, harness.useCaseProbe.resetCalls)
            assertTrue(harness.resetEffects.isEmpty())
        }

    @Test
    fun `confirmReset is a no-op when token does not match`() =
        runTest(StandardTestDispatcher()) {
            val harness = harness(defaultDispatcher = StandardTestDispatcher(testScheduler))
            harness.state.update { it.copy(resetConfirmationInput = "nope") }

            harness.coordinator.confirmReset()
            testScheduler.advanceUntilIdle()

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

            testScheduler.advanceUntilIdle()

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

            testScheduler.advanceUntilIdle()

            // resetting is cleared (state-then-effect) and exactly one Wiped emitted.
            assertEquals(1, harness.useCaseProbe.resetCalls)
            assertEquals(listOf(BackupResetEffect.Wiped), harness.resetEffects)
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

    private fun TestScope.harness(
        defaultDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ): Harness {
        val state = MutableStateFlow(BackupRestoreUiState())
        val effects = mutableListOf<BackupResetEffect>()
        val probe = UseCaseProbe()
        val useCase =
            ResetAllSettingsUseCase(
                resetEventRecorder = FakeResetEventRecorder(),
                groupRepository = FakeGroupRepository(),
                ruleDao = FakeRuleDao(),
                settingsRepository = FakeAppSettingsRepository(),
                profileMutations = NoOpProfileMutationCoordinator,
                userProfileResetStore = UserProfileResetStore { },
                diagnosticsHistoryResetStore = FakeDiagnosticsHistoryResetStore(),
                userArtifactResetStore = UserArtifactResetStore { },
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

    private class FakeGroupRepository : ProxyGroupRepository {
        private val state = MutableStateFlow(emptyList<ProxyGroup>())

        override suspend fun add(group: ProxyGroup) = Unit

        override suspend fun update(group: ProxyGroup) = Unit

        override suspend fun delete(id: String) = Unit

        override suspend fun list(): List<ProxyGroup> = emptyList()

        override suspend fun replaceAll(groups: List<ProxyGroup>) = Unit

        override fun groups(): Flow<List<ProxyGroup>> = state.asStateFlow()
    }

    private class FakeAppSettingsRepository : AppSettingsRepository {
        private val state = MutableStateFlow(AppSettings.getDefaultInstance())

        override val settings: Flow<AppSettings> = state.asStateFlow()

        override suspend fun snapshot(): AppSettings = state.first()

        override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
            state.value =
                state.value
                    .toBuilder()
                    .apply(transform)
                    .build()
        }

        override suspend fun replace(settings: AppSettings) {
            state.value = settings
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

    private class FakeRuleDao : RuleDao() {
        override suspend fun deleteAll() = Unit

        override fun allRules(): Flow<List<RuleEntity>> = flowOf(emptyList())

        override fun enabledRules(): Flow<List<RuleEntity>> = flowOf(emptyList())

        override fun rulesExcludingName(excludedName: String): Flow<List<RuleEntity>> = flowOf(emptyList())

        override fun rulesByName(name: String): Flow<List<RuleEntity>> = flowOf(emptyList())

        override suspend fun findByName(name: String): RuleEntity? = null

        override suspend fun maxUserOrder(excludedName: String): Int? = null

        override suspend fun insert(rule: RuleEntity): Long = error("not used")

        override suspend fun update(rule: RuleEntity) = error("not used")

        override suspend fun delete(rule: RuleEntity) = error("not used")

        override suspend fun resetOutboundTags(
            ruleIds: List<Long>,
            targetTag: OutboundTag,
            replacementTag: OutboundTag,
        ): Int = error("not used")

        override suspend fun updateOrder(
            id: Long,
            order: Int,
        ) = error("not used")
    }
}
