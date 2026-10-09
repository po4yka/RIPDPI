package com.poyka.ripdpi.ui.screens.subscription

import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.DurableCommandReceipt
import com.poyka.ripdpi.data.ProfileMutationPreparation
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.subscription.SubscriptionExpiryClock
import com.poyka.ripdpi.subscription.SubscriptionRefreshResult
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
import org.junit.Assert.assertNull
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

    private fun newViewModel(refresh: suspend (String) -> SubscriptionRefreshResult) =
        SubscriptionStatusViewModel(
            EmptyGroups(),
            object : SubscriptionExpiryClock {
                override fun nowMillis(): Long = 0

                override fun ticks(): Flow<Long> = flowOf(0)
            },
            refresh,
        )

    private class EmptyGroups : ProxyGroupRepository {
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
        ) = Unit

        override suspend fun list(): List<ProxyGroup> = emptyList()

        override fun groups(): Flow<List<ProxyGroup>> = flowOf(emptyList())
    }
}
