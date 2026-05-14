package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SubscriptionKind
import com.poyka.ripdpi.ui.screens.proxyimport.ProfileImportConfirmViewModel
import com.poyka.ripdpi.ui.screens.proxyimport.SubscriptionImportConfirmViewModel
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests for the minimal import-confirmation destinations. Each confirmation screen shows
 * the parsed profile / subscription and an "Add" action that persists it through
 * [ProxyGroupRepository]. These are the import-confirmation surface, not full editors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportConfirmViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `confirming a profile import persists it into a basic group`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val profile =
                ProxyProfile.Vless(
                    id = "p1",
                    displayName = "Tokyo",
                    groupId = "",
                    server = "example.com",
                    serverPort = 443,
                    uuid = "uuid",
                )
            val viewModel = ProfileImportConfirmViewModel(repository)

            viewModel.setProfile(profile)
            viewModel.confirm()
            advanceUntilIdle()

            val groups = repository.list()
            assertEquals(1, groups.size)
            assertEquals(ProxyGroupType.BASIC, groups.single().type)
            assertTrue(viewModel.uiState.value.imported)
        }

    @Test
    fun `confirming a subscription import persists a subscription group`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val viewModel = SubscriptionImportConfirmViewModel(repository)

            viewModel.setRequest(url = "https://sub.example.com/c", name = "Fleet", bootstrap = false)
            viewModel.confirm()
            advanceUntilIdle()

            val groups = repository.list()
            assertEquals(1, groups.size)
            val group = groups.single()
            assertEquals(ProxyGroupType.SUBSCRIPTION, group.type)
            assertEquals("Fleet", group.name)
            assertEquals("https://sub.example.com/c", group.subscription?.link)
            assertTrue(viewModel.uiState.value.imported)
        }

    @Test
    fun `subscription import without a name falls back to the host as the group name`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val viewModel = SubscriptionImportConfirmViewModel(repository)

            viewModel.setRequest(url = "https://sub.example.com/c", name = "", bootstrap = false)
            viewModel.confirm()
            advanceUntilIdle()

            assertEquals("sub.example.com", repository.list().single().name)
        }

    @Test
    fun `bootstrap subscription import is surfaced in ui state`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val viewModel = SubscriptionImportConfirmViewModel(repository)

            viewModel.setRequest(
                url = "https://sub.example.com/bootstrap/tok",
                name = "Boot",
                bootstrap = true,
            )

            assertTrue(viewModel.uiState.value.bootstrap)
        }

    @Test
    fun `confirming a bootstrap import persists a bootstrap-kind subscription`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val viewModel = SubscriptionImportConfirmViewModel(repository)

            viewModel.setRequest(
                url = "https://sub.example.com/bootstrap/tok",
                name = "Boot",
                bootstrap = true,
            )
            viewModel.confirm()
            advanceUntilIdle()

            assertEquals(
                SubscriptionKind.BOOTSTRAP,
                repository
                    .list()
                    .single()
                    .subscription
                    ?.kind,
            )
        }

    @Test
    fun `confirming a non-bootstrap import persists a long-lived subscription`() =
        runTest {
            val repository = FakeProxyGroupRepository()
            val viewModel = SubscriptionImportConfirmViewModel(repository)

            viewModel.setRequest(url = "https://sub.example.com/sub/x", name = "Fleet", bootstrap = false)
            viewModel.confirm()
            advanceUntilIdle()

            assertEquals(
                SubscriptionKind.LONG_LIVED,
                repository
                    .list()
                    .single()
                    .subscription
                    ?.kind,
            )
        }
}

private class FakeProxyGroupRepository : ProxyGroupRepository {
    private val state = MutableStateFlow<List<ProxyGroup>>(emptyList())

    override suspend fun add(group: ProxyGroup) {
        state.value = state.value.filterNot { it.id == group.id } + group
    }

    override suspend fun update(group: ProxyGroup) {
        state.value = state.value.map { if (it.id == group.id) group else it }
    }

    override suspend fun delete(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun list(): List<ProxyGroup> = state.value

    override fun groups(): Flow<List<ProxyGroup>> = state.asStateFlow()
}
