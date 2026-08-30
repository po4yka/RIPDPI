package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WsTunnelWorkerCredentialStoreTest {
    @Test
    fun `unconfigured transport does not read credential storage`() =
        runTest {
            val store = FakeWorkerCredentialStore(null, failOnLoad = true)

            assertNull(store.resolveTransport(AppSettings.getDefaultInstance()))
        }

    @Test
    fun `configured transport resolves bearer without exposing it in debug output`() =
        runTest {
            val store = FakeWorkerCredentialStore("operator-secret")
            val settings =
                AppSettings
                    .newBuilder()
                    .setWsTunnelWorkerUrl("https://edge.example.workers.dev/relay")
                    .setWsTunnelWorkerCredentialRef("worker-main")
                    .build()

            val config = store.resolveTransport(settings)

            assertEquals("https://edge.example.workers.dev/relay", config?.workerUrl)
            assertEquals("worker-main", config?.credentialRef)
            assertEquals("operator-secret", config?.authBearer?.value)
            assertFalse(config.toString().contains("operator-secret"))
        }

    @Test
    fun `configured transport fails closed when credential is missing`() {
        val settings =
            AppSettings
                .newBuilder()
                .setWsTunnelWorkerUrl("https://edge.example.workers.dev/relay")
                .setWsTunnelWorkerCredentialRef("worker-main")
                .build()

        assertThrows(IllegalArgumentException::class.java) {
            runTest { FakeWorkerCredentialStore(null).resolveTransport(settings) }
        }
    }

    @Test
    fun `corrupt encrypted credential propagates instead of falling back to direct`() =
        runTest {
            val backend =
                object : WsTunnelWorkerCredentialBackend {
                    override fun getStringStrict(key: String): String? =
                        throw SecurityException("ciphertext authentication failed")

                    override fun putString(
                        key: String,
                        value: String,
                    ) = Unit

                    override fun remove(key: String) = Unit

                    override fun clear() = Unit
                }
            val store = KeystoreWsTunnelWorkerCredentialStore(backend)

            val failure = runCatching { store.load("worker-main") }.exceptionOrNull()

            assertEquals(SecurityException::class, failure?.javaClass?.kotlin)
        }
}

private class FakeWorkerCredentialStore(
    private val bearer: String?,
    private val failOnLoad: Boolean = false,
) : WsTunnelWorkerCredentialStore {
    override suspend fun load(credentialRef: String): String? {
        check(!failOnLoad) { "credential store must not be read" }
        return bearer
    }

    override suspend fun save(
        credentialRef: String,
        bearer: String,
    ) = Unit

    override suspend fun clear(credentialRef: String) = Unit

    override suspend fun clearAll() = Unit
}
