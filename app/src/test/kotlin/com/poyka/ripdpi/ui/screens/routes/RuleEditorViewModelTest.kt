package com.poyka.ripdpi.ui.screens.routes

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.rules.OutboundTag
import com.poyka.ripdpi.data.rules.RuleDao
import com.poyka.ripdpi.data.rules.RuleEntity
import com.poyka.ripdpi.data.rules.RuleRepository
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.services.InstalledPackagesProvider
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RuleEditorViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `save before hydration leaves an existing rule unchanged`() =
        runTest {
            val existing = RuleEntity(id = 7L, name = "Original", domains = "old.example", ports = "443")
            val dao = FakeRuleDao(listOf(existing))
            val viewModel = newViewModel(ruleId = existing.id, dao = dao, holdHydration = true)

            viewModel.setDomains("new.example")
            viewModel.save {}
            runCurrent()

            assertEquals(existing, dao.rules.single())
            assertFalse(viewModel.uiState.value.loaded)
        }

    @Test
    fun `repeated save while insertion is pending writes once`() =
        runTest {
            val dao = FakeRuleDao(emptyList())
            val viewModel = newViewModel(ruleId = 0L, dao = dao, holdHydration = false)
            viewModel.uiState.first { it.loaded }
            val insertGate = CompletableDeferred<Unit>()
            dao.insertGate = insertGate

            viewModel.setDomains("example.com")
            viewModel.save {}
            viewModel.save {}
            assertEquals(1, dao.insertCalls)

            insertGate.complete(Unit)
            runCurrent()
            viewModel.save {}
            assertEquals(1, dao.rules.size)
        }

    private fun newViewModel(
        ruleId: Long,
        dao: FakeRuleDao,
        holdHydration: Boolean,
    ): RuleEditorViewModel {
        val groups = FakeProxyGroupRepository(holdHydration)
        val relays = FakeRelayProfileStore()
        val context = ApplicationProvider.getApplicationContext<Context>()
        return RuleEditorViewModel(
            SavedStateHandle(mapOf("ruleId" to ruleId)),
            RuleRepository(dao),
            OutboundTargetCatalog(
                groups,
                relays,
                object : StringResolver {
                    override fun getString(
                        resId: Int,
                        vararg formatArgs: Any,
                    ): String = "label"
                },
            ),
            InstalledAppCatalog(
                context,
                object : InstalledPackagesProvider {
                    override fun installedPackages(): Set<String> = emptySet()
                },
            ),
        )
    }

    private class FakeRuleDao(
        initial: List<RuleEntity>,
    ) : RuleDao() {
        val rules = initial.toMutableList()
        var insertGate: CompletableDeferred<Unit>? = null
        var insertCalls = 0

        override fun allRules(): Flow<List<RuleEntity>> = flowOf(rules.toList())

        override fun enabledRules(): Flow<List<RuleEntity>> = flowOf(rules.toList())

        override fun rulesExcludingName(excludedName: String): Flow<List<RuleEntity>> = flowOf(rules.toList())

        override fun rulesByName(name: String): Flow<List<RuleEntity>> = flowOf(emptyList())

        override suspend fun findByName(name: String): RuleEntity? = null

        override suspend fun maxUserOrder(excludedName: String): Int? = null

        override suspend fun insert(rule: RuleEntity): Long {
            insertCalls++
            insertGate?.await()
            rules.add(rule)
            return rules.size.toLong()
        }

        override suspend fun update(rule: RuleEntity) {
            rules.replaceAll { if (it.id == rule.id) rule else it }
        }

        override suspend fun delete(rule: RuleEntity) {
            rules.remove(rule)
        }

        override suspend fun deleteAll() {
            rules.clear()
        }

        override suspend fun resetOutboundTags(
            ruleIds: List<Long>,
            targetTag: OutboundTag,
            replacementTag: OutboundTag,
        ): Int = 0

        override suspend fun updateOrder(
            id: Long,
            order: Int,
        ) = Unit
    }

    private class FakeProxyGroupRepository(
        private val holdHydration: Boolean,
    ) : ProxyGroupRepository {
        private val pendingGroups = MutableSharedFlow<List<ProxyGroup>>()

        override suspend fun add(group: ProxyGroup) = Unit

        override suspend fun update(group: ProxyGroup) = Unit

        override suspend fun replaceAll(
            receipt: com.poyka.ripdpi.data.DurableCommandReceipt,
            groups: List<ProxyGroup>,
        ) {
            val preparation =
                com.poyka.ripdpi.data.ProfileMutationPreparation(
                    com.poyka.ripdpi.data.ProfileMutationOrigin.Compensation,
                    receipt.authority,
                )
            list().forEach { delete(preparation, it.id) }
            groups.forEach { add(it) }
        }

        override suspend fun compensateReplacement(groups: List<ProxyGroup>) {
            replaceAll(
                com.poyka.ripdpi.data
                    .testPauseAuthority()
                    .reserveStop(),
                groups,
            )
        }

        override suspend fun delete(
            preparation: com.poyka.ripdpi.data.ProfileMutationPreparation,
            id: String,
        ) = Unit

        override suspend fun list(): List<ProxyGroup> = emptyList()

        override fun groups(): Flow<List<ProxyGroup>> = if (holdHydration) pendingGroups else flowOf(emptyList())
    }

    private class FakeRelayProfileStore : RelayProfileStore {
        override suspend fun load(profileId: String): RelayProfileRecord? = null

        override suspend fun list(): List<RelayProfileRecord> = emptyList()

        override suspend fun save(profile: RelayProfileRecord) = Unit

        override suspend fun clear(profileId: String) = Unit
    }
}
