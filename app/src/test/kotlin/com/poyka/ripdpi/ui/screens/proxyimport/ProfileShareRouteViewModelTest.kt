package com.poyka.ripdpi.ui.screens.proxyimport

import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialRepository
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfileShareRouteViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `an undecodable stored record shows as unshareable instead of crashing`() =
        runTest {
            val viewModel = ProfileShareRouteViewModel(UndecodableProfileStore(), EmptyCredentials())

            viewModel.load("broken")
            val state =
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { viewModel.uiState.first { !it.loading } }
                }

            assertTrue(state.unshareable)
            assertNull(state.shareUri)
        }

    private class UndecodableProfileStore : RelayProfileStore {
        override suspend fun load(profileId: String): RelayProfileRecord? =
            throw SerializationException("schema-incompatible record")

        override suspend fun list(): List<RelayProfileRecord> = emptyList()

        override suspend fun save(profile: RelayProfileRecord) = Unit

        override suspend fun clear(profileId: String) = Unit
    }

    private class EmptyCredentials : RelayCredentialRepository {
        override suspend fun load(profileId: String): RelayCredentialRecord? = null

        override suspend fun save(credentials: RelayCredentialRecord) = Unit

        override suspend fun clear(profileId: String) = Unit
    }
}
