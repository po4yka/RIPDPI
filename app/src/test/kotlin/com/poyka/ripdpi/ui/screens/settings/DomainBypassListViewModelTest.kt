package com.poyka.ripdpi.ui.screens.settings

import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RuleDao
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.data.rules.RuleRepository
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DomainBypassListViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `repository refresh does not replace unsaved draft`() =
        runTest {
            val dao = FakeRuleDao()
            val viewModel = DomainBypassListViewModel(RuleRepository(dao))
            runCurrent()

            viewModel.updateDraft("example.com")
            dao.emitManagedRules()
            runCurrent()

            assertEquals("example.com", viewModel.uiState.value.text)
        }

    private class FakeRuleDao : RuleDao() {
        private val managedRules = MutableSharedFlow<List<RuleEntity>>(replay = 1).apply { tryEmit(emptyList()) }

        fun emitManagedRules() {
            managedRules.tryEmit(emptyList())
        }

        override suspend fun deleteAll() = Unit

        override fun allRules(): Flow<List<RuleEntity>> = flowOf(emptyList())

        override fun enabledRules(): Flow<List<RuleEntity>> = flowOf(emptyList())

        override fun rulesExcludingName(excludedName: String): Flow<List<RuleEntity>> = flowOf(emptyList())

        override fun rulesByName(name: String): Flow<List<RuleEntity>> = managedRules

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
