package com.poyka.ripdpi.backup

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHistoryResetStore
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RuleDao
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResetAllSettingsUseCaseTest {
    private val sampleProfile =
        ProxyProfile.Shadowsocks(
            id = "ss-1",
            displayName = "SS",
            groupId = "g-1",
            server = "ss.example.com",
            serverPort = 8388,
            method = "aes-256-gcm",
            password = "secret",
        )
    private val sampleGroup =
        ProxyGroup(
            id = "g-1",
            name = "Group",
            type = ProxyGroupType.BASIC,
            order = 0,
            isSelector = false,
            members = listOf(sampleProfile),
        )

    private fun useCase(
        recorder: ResetEventRecorder,
        groups: ProxyGroupRepository,
        rules: RuleDao,
        settings: AppSettingsRepository,
        diagnostics: DiagnosticsHistoryResetStore,
        caches: CacheDirectoryCleaner,
    ) = ResetAllSettingsUseCase(
        resetEventRecorder = recorder,
        groupRepository = groups,
        ruleDao = rules,
        settingsRepository = settings,
        diagnosticsHistoryResetStore = diagnostics,
        cacheDirectoryCleaner = caches,
    )

    @Test
    fun `reset wipes every user store`() =
        runTest {
            val rules = FakeRuleDao(initialRowCount = 2)

            val recorder = FakeResetEventRecorder()
            val groups = FakeGroupRepository(mutableListOf(sampleGroup))
            val settings =
                FakeAppSettingsRepository(
                    AppSettings
                        .getDefaultInstance()
                        .toBuilder()
                        .setProxyPort(4242)
                        .build(),
                )
            val diagnostics = FakeDiagnosticsHistoryResetStore()
            val caches = FakeCacheDirectoryCleaner()

            useCase(recorder, groups, rules, settings, diagnostics, caches).reset()

            // Groups and their embedded profiles are emptied.
            assertTrue(groups.list().isEmpty())
            // Routing rules deleted.
            assertEquals(0, rules.rowCount)
            assertEquals(1, rules.deleteAllCalls)
            // Settings back to the proto default (custom proxyPort gone).
            assertEquals(AppSettings.getDefaultInstance().proxyPort, settings.snapshot().proxyPort)
            // Diagnostics user-history cleared.
            assertEquals(1, diagnostics.clearCalls)
            // Caches cleared.
            assertEquals(1, caches.clearCalls)
        }

    @Test
    fun `reset records the user_initiated_reset event before any wipe`() =
        runTest {
            val recorder = FakeResetEventRecorder()
            // The group repository snapshots whether the event was already recorded
            // when the first store wipe runs, proving telemetry precedes the wipe.
            val groups = FakeGroupRepository(mutableListOf(sampleGroup), onReplace = { recorder.recordCalls })

            useCase(
                recorder = recorder,
                groups = groups,
                rules = FakeRuleDao(initialRowCount = 1),
                settings = FakeAppSettingsRepository(),
                diagnostics = FakeDiagnosticsHistoryResetStore(),
                caches = FakeCacheDirectoryCleaner(),
            ).reset()

            assertEquals(1, recorder.recordCalls)
            // The recorder had already been called once by the time the first wipe ran.
            assertEquals(1, groups.recordCallsAtFirstWrite)
        }

    @Test
    fun `reset event survives the wipe in the recorder and is consumable once`() =
        runTest {
            val recorder = FakeResetEventRecorder()
            useCase(
                recorder = recorder,
                groups = FakeGroupRepository(mutableListOf(sampleGroup)),
                rules = FakeRuleDao(initialRowCount = 1),
                settings = FakeAppSettingsRepository(),
                diagnostics = FakeDiagnosticsHistoryResetStore(),
                caches = FakeCacheDirectoryCleaner(),
            ).reset()

            assertTrue(recorder.hasPendingResetEvent())
            // Consumed exactly once across the (simulated) restart boundary.
            assertTrue(recorder.consumeResetEvent())
            assertFalse(recorder.consumeResetEvent())
            assertFalse(recorder.hasPendingResetEvent())
        }

    // -- Fakes ----------------------------------------------------------------

    private class FakeResetEventRecorder : ResetEventRecorder {
        var recordCalls = 0
            private set
        private var pending = false

        override fun recordResetInitiated() {
            recordCalls++
            pending = true
        }

        override fun hasPendingResetEvent(): Boolean = pending

        override fun consumeResetEvent(): Boolean {
            if (!pending) return false
            pending = false
            return true
        }
    }

    private class FakeGroupRepository(
        private val groups: MutableList<ProxyGroup>,
        private val onReplace: (() -> Int)? = null,
    ) : ProxyGroupRepository {
        private val state = MutableStateFlow(groups.toList())
        private var replaceAllCalls = 0

        /** [FakeResetEventRecorder.recordCalls] captured at the first wipe. */
        var recordCallsAtFirstWrite = 0
            private set

        override suspend fun add(group: ProxyGroup) {
            groups.removeAll { it.id == group.id }
            groups.add(group)
            state.value = groups.toList()
        }

        override suspend fun update(group: ProxyGroup) {
            val idx = groups.indexOfFirst { it.id == group.id }
            if (idx >= 0) groups[idx] = group
            state.value = groups.toList()
        }

        override suspend fun delete(id: String) {
            groups.removeAll { it.id == id }
            state.value = groups.toList()
        }

        override suspend fun list(): List<ProxyGroup> = groups.toList()

        override suspend fun replaceAll(groups: List<ProxyGroup>) {
            if (replaceAllCalls++ == 0) recordCallsAtFirstWrite = onReplace?.invoke() ?: 0
            this.groups.clear()
            this.groups.addAll(groups)
            state.value = this.groups.toList()
        }

        override fun groups(): Flow<List<ProxyGroup>> = state.asStateFlow()
    }

    private class FakeAppSettingsRepository(
        current: AppSettings = AppSettings.getDefaultInstance(),
    ) : AppSettingsRepository {
        private val state = MutableStateFlow(current)

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
        var clearCalls = 0
            private set

        override suspend fun clearRuntimeHistory() {
            clearCalls++
        }
    }

    private class FakeCacheDirectoryCleaner : CacheDirectoryCleaner {
        var clearCalls = 0
            private set

        override fun clearCaches() {
            clearCalls++
        }
    }

    /**
     * Minimal in-memory [RuleDao] for the reset path. Only [deleteAll] is exercised;
     * the remaining query/mutation methods are not called by the use case and would
     * fail loudly if a regression started invoking them.
     */
    private class FakeRuleDao(
        initialRowCount: Int,
    ) : RuleDao() {
        var rowCount: Int = initialRowCount
            private set
        var deleteAllCalls: Int = 0
            private set

        override suspend fun deleteAll() {
            deleteAllCalls++
            rowCount = 0
        }

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
