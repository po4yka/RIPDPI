package com.poyka.ripdpi.ui.screens.proxyimport

import com.poyka.ripdpi.data.ProfileMutationRecoveryAccess
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialRepository
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.testsupport.NoOpProfileMutationCoordinator
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.net.URI
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ProfileShareRouteViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `an undecodable stored record shows as unshareable instead of crashing`() =
        runTest {
            val viewModel =
                ProfileShareRouteViewModel(
                    UndecodableProfileStore(),
                    EmptyCredentials(),
                    NoOpProfileMutationCoordinator,
                )

            viewModel.load("broken")
            val state =
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { viewModel.uiState.first { !it.loading } }
                }

            assertTrue(state.unshareable)
            assertNull(state.shareUri)
        }

    @Test
    fun `sharing reads matching generations across a concurrent edit`() =
        runTest {
            val mutex = Mutex()
            val recordRead = CompletableDeferred<Unit>()
            val finishRead = CompletableDeferred<Unit>()
            val writerStarted = CompletableDeferred<Unit>()
            var record = testProfile("node", "before.example")
            var credentials = RelayCredentialRecord(profileId = "node", trojanPassword = "before")
            val profiles =
                object : RelayProfileStore by UndecodableProfileStore() {
                    override suspend fun load(profileId: String): RelayProfileRecord {
                        val snapshot = record
                        recordRead.complete(Unit)
                        finishRead.await()
                        return snapshot
                    }
                }
            val credentialRepository =
                object : RelayCredentialRepository by EmptyCredentials() {
                    override suspend fun load(profileId: String): RelayCredentialRecord = credentials
                }
            val recovery =
                object : ProfileMutationRecoveryAccess by NoOpProfileMutationCoordinator {
                    override suspend fun <T> readRecovered(block: suspend () -> T): T = mutex.withLock { block() }
                }
            val viewModel = ProfileShareRouteViewModel(profiles, credentialRepository, recovery)

            viewModel.load("node")
            recordRead.await()
            val writer =
                launch {
                    writerStarted.complete(Unit)
                    mutex.withLock {
                        record = testProfile("node", "after.example")
                        credentials = credentials.copy(trojanPassword = "after")
                    }
                }
            writerStarted.await()
            finishRead.complete(Unit)

            val shared = URI(requireNotNull(viewModel.uiState.first { !it.loading }.shareUri))
            writer.join()
            assertEquals("before.example", shared.host)
            assertEquals("before", shared.userInfo)
            assertEquals("after.example", record.server)
        }

    @Test
    fun `a completed older load cannot replace the requested share target`() =
        runTest {
            val stillReading = CompletableDeferred<Continuation<Unit>>()
            val profiles =
                object : RelayProfileStore by UndecodableProfileStore() {
                    override suspend fun load(profileId: String): RelayProfileRecord {
                        if (profileId == "first") suspendCoroutine { stillReading.complete(it) }
                        return testProfile(profileId, "$profileId.example")
                    }
                }
            val credentials =
                object : RelayCredentialRepository by EmptyCredentials() {
                    override suspend fun load(profileId: String): RelayCredentialRecord =
                        RelayCredentialRecord(profileId = profileId, trojanPassword = "fixture")
                }
            val viewModel = ProfileShareRouteViewModel(profiles, credentials, NoOpProfileMutationCoordinator)

            viewModel.load("first")
            val firstRead = stillReading.await()
            viewModel.load("second")
            viewModel.uiState.first { !it.loading && it.profileId == "second" }
            firstRead.resume(Unit)

            assertEquals("second", viewModel.uiState.value.profileId)
        }

    private fun testProfile(
        id: String,
        server: String,
    ): RelayProfileRecord = RelayProfileRecord(id = id, kind = RelayKindTrojan, server = server, udpEnabled = true)

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
