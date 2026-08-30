package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WsTunnelWorkerTransportProvisionerTest {
    @Test
    fun `provision stores bearer before publishing URL and reference`() =
        runTest {
            val repository = FakeSettingsRepository()
            val store = FakeCredentialStore()
            val provisioner = WsTunnelWorkerTransportProvisioner(repository, store)

            provisioner.provision(WorkerUrl, CredentialRef, SecretString(Bearer))

            assertEquals(Bearer, store.credentials[CredentialRef])
            assertEquals(WorkerUrl, repository.snapshot().wsTunnelWorkerUrl)
            assertEquals(CredentialRef, repository.snapshot().wsTunnelWorkerCredentialRef)
            assertEquals(listOf("save:$CredentialRef", "settings:update"), store.events + repository.events)
        }

    @Test
    fun `settings failure restores overwritten credential`() =
        runTest {
            val repository = FakeSettingsRepository(updateFailure = IllegalStateException("disk full"))
            val store = FakeCredentialStore(mutableMapOf(CredentialRef to "previous"))
            val provisioner = WsTunnelWorkerTransportProvisioner(repository, store)

            val failure =
                runCatching {
                    provisioner.provision(WorkerUrl, CredentialRef, SecretString(Bearer))
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals("previous", store.credentials[CredentialRef])
            assertEquals("", repository.snapshot().wsTunnelWorkerUrl)
        }

    @Test
    fun `cancelled settings write rolls back new credential`() =
        runTest {
            val repository = FakeSettingsRepository(updateFailure = CancellationException("cancelled"))
            val store = FakeCredentialStore()
            val provisioner = WsTunnelWorkerTransportProvisioner(repository, store)

            val failure =
                runCatching {
                    provisioner.provision(WorkerUrl, CredentialRef, SecretString(Bearer))
                }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertFalse(CredentialRef in store.credentials)
        }

    @Test
    fun `rotating reference clears the previous credential`() =
        runTest {
            val repository =
                FakeSettingsRepository(
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setWsTunnelWorkerUrl("https://old.example/ws")
                        .setWsTunnelWorkerCredentialRef("old")
                        .build(),
                )
            val store = FakeCredentialStore(mutableMapOf("old" to "old-secret"))
            val provisioner = WsTunnelWorkerTransportProvisioner(repository, store)

            provisioner.provision(WorkerUrl, CredentialRef, SecretString(Bearer))

            assertFalse("old" in store.credentials)
            assertEquals(Bearer, store.credentials[CredentialRef])
        }

    @Test
    fun `rotation cleanup failure rolls back the new credential and keeps old settings`() =
        runTest {
            val repository =
                FakeSettingsRepository(
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setWsTunnelWorkerUrl("https://old.example/ws")
                        .setWsTunnelWorkerCredentialRef("old")
                        .build(),
                )
            val store =
                FakeCredentialStore(
                    credentials = mutableMapOf("old" to "old-secret"),
                    clearFailureRef = "old",
                )

            val failure =
                runCatching {
                    WsTunnelWorkerTransportProvisioner(repository, store)
                        .provision(WorkerUrl, CredentialRef, SecretString(Bearer))
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals("old-secret", store.credentials["old"])
            assertFalse(CredentialRef in store.credentials)
            assertEquals("old", repository.snapshot().wsTunnelWorkerCredentialRef)
        }

    @Test
    fun `clear removes route and credential`() =
        runTest {
            val repository =
                FakeSettingsRepository(
                    AppSettingsSerializer.defaultValue
                        .toBuilder()
                        .setWsTunnelWorkerUrl(WorkerUrl)
                        .setWsTunnelWorkerCredentialRef(CredentialRef)
                        .build(),
                )
            val store = FakeCredentialStore(mutableMapOf(CredentialRef to Bearer))

            WsTunnelWorkerTransportProvisioner(repository, store).clear()

            assertEquals("", repository.snapshot().wsTunnelWorkerUrl)
            assertEquals("", repository.snapshot().wsTunnelWorkerCredentialRef)
            assertFalse(CredentialRef in store.credentials)
        }

    private class FakeSettingsRepository(
        initial: AppSettings = AppSettingsSerializer.defaultValue,
        private val updateFailure: Throwable? = null,
    ) : AppSettingsRepository {
        private val state = MutableStateFlow(initial)
        val events = mutableListOf<String>()
        override val settings: Flow<AppSettings> = state

        override suspend fun snapshot(): AppSettings = state.value

        override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
            events += "settings:update"
            updateFailure?.let { throw it }
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

    private class FakeCredentialStore(
        val credentials: MutableMap<String, String> = mutableMapOf(),
        private val clearFailureRef: String? = null,
    ) : WsTunnelWorkerCredentialStore {
        val events = mutableListOf<String>()

        override suspend fun load(credentialRef: String): String? = credentials[credentialRef]

        override suspend fun save(
            credentialRef: String,
            bearer: String,
        ) {
            events += "save:$credentialRef"
            credentials[credentialRef] = bearer
        }

        override suspend fun clear(credentialRef: String) {
            events += "clear:$credentialRef"
            check(credentialRef != clearFailureRef) { "credential clear failed" }
            credentials.remove(credentialRef)
        }

        override suspend fun clearAll() {
            credentials.clear()
        }
    }

    private companion object {
        const val WorkerUrl = "https://worker.example/ws"
        const val CredentialRef = "worker-production"
        const val Bearer = "worker-secret"
    }
}
