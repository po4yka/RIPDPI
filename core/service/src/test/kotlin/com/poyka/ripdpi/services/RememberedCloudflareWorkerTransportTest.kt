package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiWsTunnelConfig
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.SecretString
import com.poyka.ripdpi.data.WsTunnelWorkerCredentialStore
import com.poyka.ripdpi.data.WsTunnelWorkerTransportProvisioner
import com.poyka.ripdpi.proto.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RememberedCloudflareWorkerTransportTest {
    @Test
    fun `credential rotation overlays current route onto remembered policy`() =
        runTest {
            val preferences = rememberedPreferences(OldWorkerUrl, OldCredentialRef)
            val repository = FakeSettingsRepository(currentSettings(OldWorkerUrl, OldCredentialRef))
            val store = MapCredentialStore(mutableMapOf(OldCredentialRef to OldBearer))
            WsTunnelWorkerTransportProvisioner(repository, store)
                .provision(NewWorkerUrl, NewCredentialRef, SecretString(NewBearer))

            val resolved = ProxySessionSecretResolver(store).applyRemembered(preferences, repository.snapshot())

            assertEquals(NewWorkerUrl, resolved.wsTunnel.cloudflareWorkerUrl)
            assertEquals(NewCredentialRef, resolved.wsTunnel.cloudflareWorkerCredentialRef)
            assertEquals(NewBearer, resolved.wsTunnel.cloudflareWorkerBearer?.value)
            assertTrue(OldCredentialRef !in store.credentials)
        }

    @Test
    fun `remembered route fails closed after its credential is cleared`() =
        runTest {
            val preferences = rememberedPreferences(OldWorkerUrl, OldCredentialRef)

            val failure =
                runCatching {
                    ProxySessionSecretResolver(MapCredentialStore()).applyRemembered(
                        preferences,
                        currentSettings(OldWorkerUrl, OldCredentialRef),
                    )
                }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
        }

    @Test
    fun `explicit provisioner clear strips Worker route from remembered policy`() =
        runTest {
            val preferences = rememberedPreferences(OldWorkerUrl, OldCredentialRef)
            val repository = FakeSettingsRepository(currentSettings(OldWorkerUrl, OldCredentialRef))
            val store = MapCredentialStore(mutableMapOf(OldCredentialRef to OldBearer))
            val provisioner = WsTunnelWorkerTransportProvisioner(repository, store)

            provisioner.clear()
            val resolved = ProxySessionSecretResolver(store).applyRemembered(preferences, repository.snapshot())

            assertEquals(null, resolved.wsTunnel.cloudflareWorkerUrl)
            assertEquals(null, resolved.wsTunnel.cloudflareWorkerCredentialRef)
            assertEquals(null, resolved.wsTunnel.cloudflareWorkerBearer)
        }

    @Test
    fun `remembered route rejects fake SNI before policy activation`() =
        runTest {
            val preferences =
                RipDpiProxyUIPreferences(
                    wsTunnel =
                        RipDpiWsTunnelConfig(
                            enabled = true,
                            mode = "always",
                            fakeSni = "cover.example",
                            allowInsecureSni = true,
                            cloudflareWorkerUrl = OldWorkerUrl,
                            cloudflareWorkerCredentialRef = OldCredentialRef,
                        ),
                )

            val failure =
                runCatching {
                    ProxySessionSecretResolver(
                        MapCredentialStore(mutableMapOf(OldCredentialRef to OldBearer)),
                    ).applyRemembered(preferences, currentSettings(OldWorkerUrl, OldCredentialRef))
                }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
        }

    private fun rememberedPreferences(
        workerUrl: String,
        credentialRef: String,
    ) = RipDpiProxyUIPreferences(
        wsTunnel =
            RipDpiWsTunnelConfig(
                enabled = true,
                mode = "always",
                cloudflareWorkerUrl = workerUrl,
                cloudflareWorkerCredentialRef = credentialRef,
            ),
    )

    private fun currentSettings(
        workerUrl: String,
        credentialRef: String,
    ): AppSettings =
        AppSettingsSerializer.defaultValue
            .toBuilder()
            .setWsTunnelWorkerUrl(workerUrl)
            .setWsTunnelWorkerCredentialRef(credentialRef)
            .build()

    private class MapCredentialStore(
        val credentials: MutableMap<String, String> = mutableMapOf(),
    ) : WsTunnelWorkerCredentialStore {
        override suspend fun load(credentialRef: String): String? = credentials[credentialRef]

        override suspend fun save(
            credentialRef: String,
            bearer: String,
        ) {
            credentials[credentialRef] = bearer
        }

        override suspend fun clear(credentialRef: String) {
            credentials.remove(credentialRef)
        }

        override suspend fun clearAll() {
            credentials.clear()
        }
    }

    private class FakeSettingsRepository(
        initial: AppSettings,
    ) : AppSettingsRepository {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = state

        override suspend fun snapshot(): AppSettings = state.value

        override suspend fun update(transform: AppSettings.Builder.() -> Unit) {
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

    private companion object {
        const val OldWorkerUrl = "https://old-worker.example/ws"
        const val OldCredentialRef = "old-worker"
        const val OldBearer = "old-secret"
        const val NewWorkerUrl = "https://new-worker.example/ws"
        const val NewCredentialRef = "new-worker"
        const val NewBearer = "new-secret"
    }
}
