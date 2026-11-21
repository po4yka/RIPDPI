package com.poyka.ripdpi.ui.screens.subscription

import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.activities.FakeAppliedRuntimeConfigurationSource
import com.poyka.ripdpi.activities.runningOn
import com.poyka.ripdpi.data.DurableCommandReceipt
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProfileMutationPreparation
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.RuntimeConfigurationSelection
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.awg.AwgSubscriptionProfile
import com.poyka.ripdpi.data.awg.TestDirectAwgProfileMutationCoordinator
import com.poyka.ripdpi.data.awg.testAwgProfileRepository
import com.poyka.ripdpi.subscription.SubscriptionExpiryClock
import com.poyka.ripdpi.subscription.SubscriptionRefreshResult
import com.poyka.ripdpi.testsupport.NoOpProfileMutationCoordinator
import com.poyka.ripdpi.ui.screens.awg.AwgPeerTestKey
import com.poyka.ripdpi.ui.screens.awg.InMemoryAwgCredentialStore
import com.poyka.ripdpi.ui.screens.awg.InMemoryAwgProfileDao
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionStatusViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `repeated requests before dispatch start only one refresh and release the gate`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val requests = mutableListOf<String>()
            val gate = CompletableDeferred<Unit>()
            val viewModel =
                newViewModel { id ->
                    requests += id
                    gate.await()
                    SubscriptionRefreshResult.Updated(0)
                }
            backgroundScope.launch { viewModel.uiState.collect {} }
            runCurrent()
            viewModel.refresh("first")
            viewModel.refresh("first")
            viewModel.refresh("second")
            runCurrent()
            assertEquals(listOf("first"), requests)
            assertEquals("first", viewModel.uiState.value.refreshingGroupId)
            gate.complete(Unit)
            runCurrent()
            assertNull(viewModel.uiState.value.refreshingGroupId)
            viewModel.refresh("second")
            runCurrent()
            assertEquals(listOf("first", "second"), requests)
            assertNull(viewModel.uiState.value.refreshingGroupId)
        }

    @Test
    fun `failed refresh reports failure releases the gate and permits retry`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            var calls = 0
            val viewModel =
                newViewModel {
                    calls++
                    when (calls) {
                        1 -> throw IOException("controlled refresh failure")
                        2 -> SubscriptionRefreshResult.Failed(SubscriptionRefreshFailure.UNREACHABLE, true)
                        else -> SubscriptionRefreshResult.Updated(1)
                    }
                }
            backgroundScope.launch { viewModel.uiState.collect {} }
            runCurrent()
            repeat(2) {
                viewModel.refresh("first")
                runCurrent()
                assertNull(viewModel.uiState.value.refreshingGroupId)
                assertEquals("first", viewModel.uiState.value.refreshFailedGroupId)
            }
            viewModel.refresh("first")
            runCurrent()
            assertEquals(3, calls)
            assertNull(viewModel.uiState.value.refreshFailedGroupId)
        }

    @Test
    fun `cancellation before launch releases busy without reporting a failure`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            var calls = 0
            val viewModel =
                newViewModel {
                    calls++
                    SubscriptionRefreshResult.Updated(0)
                }
            backgroundScope.launch { viewModel.uiState.collect {} }
            runCurrent()
            val scopeJob = viewModel.viewModelScope.coroutineContext[Job]!!
            val existing = scopeJob.children.toSet()
            viewModel.refresh("first")
            scopeJob.children.first { it !in existing }.cancel()
            runCurrent()
            assertEquals(0, calls)
            assertNull(viewModel.uiState.value.refreshingGroupId)
            assertNull(viewModel.uiState.value.refreshFailedGroupId)
            viewModel.refresh("second")
            runCurrent()
            assertEquals(1, calls)
        }

    @Test
    fun `delete removes the group as a saved edit`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val groups = EmptyGroups()
            val viewModel = newViewModel(groups)
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.delete("first")
            runCurrent()

            assertEquals(listOf("first" to ProfileMutationOrigin.SavedEdit), groups.deletions)
            assertNull(viewModel.uiState.value.deleteFailedGroupId)
        }

    @Test
    fun `a repeated delete while one runs deletes once and disables the card`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val gate = CompletableDeferred<Unit>()
            val groups = EmptyGroups(gate = gate)
            val viewModel = newViewModel(groups)
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.delete("first")
            viewModel.delete("first")
            runCurrent()

            assertEquals(setOf("first"), viewModel.uiState.value.deletingGroupIds)
            gate.complete(Unit)
            runCurrent()
            assertEquals(1, groups.deletions.size)
            assertTrue(
                viewModel.uiState.value.deletingGroupIds
                    .isEmpty(),
            )
        }

    @Test
    fun `failed delete reports the group and a retry clears it`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val groups = EmptyGroups(deleteFailure = IOException("controlled delete failure"))
            val viewModel = newViewModel(groups)
            backgroundScope.launch { viewModel.uiState.collect {} }

            viewModel.delete("first")
            runCurrent()
            assertEquals("first", viewModel.uiState.value.deleteFailedGroupId)

            groups.deleteFailure = null
            viewModel.delete("first")
            runCurrent()
            assertNull(viewModel.uiState.value.deleteFailedGroupId)
            assertEquals(2, groups.deletions.size)
        }

    @Test
    fun `a group the running session uses directly or by its AmneziaWG profile is not deleted`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val groups = EmptyGroups()
            val viewModel = newViewModel(groups)
            val awgId = awgRepository.saveSubscriptionProfiles(listOf(awgMember("second"))).single().id
            backgroundScope.launch { viewModel.uiState.collect {} }
            applied.applications.value = runningOn(RuntimeConfigurationSelection("relay", selectorGroupId = "first"))
            runCurrent()
            assertEquals(setOf("first"), viewModel.uiState.value.inUseGroupIds)

            viewModel.delete("first")
            runCurrent()
            applied.applications.value =
                runningOn(RuntimeConfigurationSelection("relay", relayKind = "amneziawg", profileId = awgId))
            runCurrent()
            assertEquals(setOf("second"), viewModel.uiState.value.inUseGroupIds)
            viewModel.delete("second")
            runCurrent()

            assertTrue(groups.deletions.isEmpty())
            assertNotNull(awgRepository.load(awgId))
        }

    @Test
    fun `delete also removes the AmneziaWG profiles that subscription imported`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val groups = EmptyGroups()
            val viewModel = newViewModel(groups)
            val (owned, other) =
                awgRepository.saveSubscriptionProfiles(listOf(awgMember("first"), awgMember("second"))).map { it.id }

            viewModel.delete("first")
            runCurrent()

            assertEquals(listOf("first" to ProfileMutationOrigin.SavedEdit), groups.deletions)
            assertNull(awgRepository.load(owned))
            assertNotNull(awgRepository.load(other))
        }

    private val applied = FakeAppliedRuntimeConfigurationSource()
    private val awgDao = InMemoryAwgProfileDao()
    private val awgCredentials = InMemoryAwgCredentialStore()
    private val awgRepository =
        testAwgProfileRepository(
            awgDao,
            awgCredentials,
            TestDirectAwgProfileMutationCoordinator(awgDao, awgCredentials),
        )

    private fun awgMember(groupId: String) =
        AwgSubscriptionProfile(
            groupId,
            "tag:home",
            "Home",
            AwgActivationRequest(
                profileId = "",
                privateKey = "",
                peerPublicKey = AwgPeerTestKey,
                endpointHost = "$groupId.example.com",
                endpointPort = 51820,
                interfaceAddressV4 = "10.8.0.2/32",
            ),
        )

    private fun newViewModel(
        groups: ProxyGroupRepository = EmptyGroups(),
        refresh: suspend (String) -> SubscriptionRefreshResult = { SubscriptionRefreshResult.Updated(0) },
    ) = SubscriptionStatusViewModel(
        groups,
        object : SubscriptionExpiryClock {
            override fun nowMillis(): Long = 0

            override fun ticks(): Flow<Long> = flowOf(0)
        },
        NoOpProfileMutationCoordinator,
        awgRepository,
        applied,
        refresh,
    )

    private class EmptyGroups(
        var deleteFailure: Exception? = null,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : ProxyGroupRepository {
        val deletions = mutableListOf<Pair<String, ProfileMutationOrigin>>()

        override suspend fun add(group: ProxyGroup) = Unit

        override suspend fun update(group: ProxyGroup) = Unit

        override suspend fun replaceAll(
            receipt: DurableCommandReceipt,
            groups: List<ProxyGroup>,
        ) = Unit

        override suspend fun compensateReplacement(groups: List<ProxyGroup>) = Unit

        override suspend fun delete(
            preparation: ProfileMutationPreparation,
            id: String,
        ) {
            gate?.await()
            deletions += id to preparation.origin
            deleteFailure?.let { throw it }
        }

        override suspend fun list(): List<ProxyGroup> = emptyList()

        override fun groups(): Flow<List<ProxyGroup>> = flowOf(emptyList())
    }
}
