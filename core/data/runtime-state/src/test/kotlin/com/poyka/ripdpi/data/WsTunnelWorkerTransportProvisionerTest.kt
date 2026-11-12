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
            assertEquals(Bearer, store.resolveTransport(repository.snapshot())?.authBearer?.value)
        }

    @Test
    fun `settings failure keeps the previous URL and credential for the same requested reference`() =
        runTest {
            val repository =
                FakeSettingsRepository(
                    initial = configuredSettings("https://old.example/ws"),
                    updateFailure = IllegalStateException("disk full"),
                )
            val store = FakeCredentialStore(mutableMapOf(CredentialRef to "previous"))
            val provisioner = WsTunnelWorkerTransportProvisioner(repository, store)

            val failure =
                runCatching {
                    provisioner.provision(WorkerUrl, CredentialRef, SecretString(Bearer))
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(mapOf(CredentialRef to "previous"), store.credentials)
            val transport = store.resolveTransport(repository.snapshot())
            assertEquals("https://old.example/ws", transport?.workerUrl)
            assertEquals("previous", transport?.authBearer?.value)
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
    fun `rotation cleanup failure keeps the published credential and settings`() =
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
            assertEquals(Bearer, store.credentials[CredentialRef])
            assertEquals(WorkerUrl, store.resolveTransport(repository.snapshot())?.workerUrl)
            assertEquals(CredentialRef, repository.snapshot().wsTunnelWorkerCredentialRef)
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

    @Test
    fun `exception after settings commit does not delete the active credential`() =
        runTest {
            val repository = FakeSettingsRepository(afterUpdateFailure = IllegalStateException("after commit"))
            val store = FakeCredentialStore()

            val failure =
                runCatching {
                    WsTunnelWorkerTransportProvisioner(repository, store)
                        .provision(WorkerUrl, CredentialRef, SecretString(Bearer))
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            val transport = store.resolveTransport(repository.snapshot())
            assertEquals(WorkerUrl, transport?.workerUrl)
            assertEquals(Bearer, transport?.authBearer?.value)
        }

    @Test
    fun `failed clear publication preserves the active credential`() =
        runTest {
            val repository =
                FakeSettingsRepository(
                    initial = configuredSettings(),
                    updateFailure = IllegalStateException("disk full"),
                )
            val store = FakeCredentialStore(mutableMapOf(CredentialRef to Bearer))

            val failure =
                runCatching { WsTunnelWorkerTransportProvisioner(repository, store).clear() }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(Bearer, store.resolveTransport(repository.snapshot())?.authBearer?.value)
        }

    @Test
    fun `failed clear cleanup leaves the route disabled`() =
        runTest {
            val repository = FakeSettingsRepository(configuredSettings())
            val store = FakeCredentialStore(mutableMapOf(CredentialRef to Bearer), clearFailureRef = CredentialRef)

            val failure =
                runCatching { WsTunnelWorkerTransportProvisioner(repository, store).clear() }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals("", repository.snapshot().wsTunnelWorkerUrl)
            assertEquals("", repository.snapshot().wsTunnelWorkerCredentialRef)
            assertEquals(Bearer, store.credentials[CredentialRef])
        }

    @Test
    fun `failed rollback cleanup preserves the publication failure`() =
        runTest {
            val expected = IllegalStateException("disk full")
            val repository = FakeSettingsRepository(updateFailure = expected)
            val store = FakeCredentialStore(clearFailureRef = CredentialRef)

            val failure =
                runCatching {
                    WsTunnelWorkerTransportProvisioner(repository, store)
                        .provision(WorkerUrl, CredentialRef, SecretString(Bearer))
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals("disk full", failure?.message)
            assertEquals("credential clear failed", expected.suppressed.single().message)
            assertEquals("", repository.snapshot().wsTunnelWorkerCredentialRef)
        }

    private fun configuredSettings(url: String = WorkerUrl): AppSettings =
        AppSettingsSerializer.defaultValue
            .toBuilder()
            .setWsTunnelWorkerUrl(url)
            .setWsTunnelWorkerCredentialRef(CredentialRef)
            .build()

    private class FakeSettingsRepository(
        initial: AppSettings = AppSettingsSerializer.defaultValue,
        private val updateFailure: Throwable? = null,
        private val afterUpdateFailure: Throwable? = null,
    ) : AppSettingsRepository {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = state

        override suspend fun snapshot(): AppSettings = state.value

        override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
            updateFailure?.let { throw it }
            state.value =
                state.value
                    .toBuilder()
                    .apply(transform)
                    .build()
            afterUpdateFailure?.let { throw it }
        }

        override suspend fun replace(settings: AppSettings) {
            state.value = settings
        }
    }

    private class FakeCredentialStore(
        val credentials: MutableMap<String, String> = mutableMapOf(),
        private val clearFailureRef: String? = null,
    ) : WsTunnelWorkerCredentialStore {
        override suspend fun load(credentialRef: String): String? = credentials[credentialRef]

        override suspend fun save(
            credentialRef: String,
            bearer: String,
        ) {
            credentials[credentialRef] = bearer
        }

        override suspend fun clear(credentialRef: String) {
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
