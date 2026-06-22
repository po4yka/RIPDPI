package com.poyka.ripdpi.ui.screens.anytls

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.proxyimport.RelayProfileActivator
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Verifies the dedicated AnyTLS editor's save path: a complete editor activates the
 * native relay (UDP-enabled) through [RelayProfileActivator] and flips `saved`, and
 * an incomplete editor is a no-op.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnyTlsProfileViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // Named *Fixture so the no-secrets pre-commit hook excludes the line.
    private val passwordFixture = "anytls-secret-fixture"

    private fun viewModel(
        profileStore: RelayProfileStore,
        credentialStore: RelayCredentialStore,
        settings: AppSettingsRepository,
    ): AnyTlsProfileViewModel = AnyTlsProfileViewModel(RelayProfileActivator(profileStore, credentialStore, settings))

    @Test
    fun `saving a complete anytls editor activates the native relay`() =
        runTest {
            val profileStore = FakeRelayProfileStore()
            val credentialStore = FakeRelayCredentialStore()
            val settings = FakeAppSettingsRepository()
            val viewModel = viewModel(profileStore, credentialStore, settings)

            viewModel.onFieldChanged(AnyTlsEditorField.SERVER, "anytls.example.com")
            viewModel.onFieldChanged(AnyTlsEditorField.SERVER_PORT, "443")
            viewModel.onFieldChanged(AnyTlsEditorField.PASSWORD, passwordFixture)
            viewModel.onSave()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.saved)
            assertFalse(viewModel.uiState.value.saving)
            assertNull(viewModel.uiState.value.errorMessage)

            val snapshot = settings.snapshot()
            assertEquals(RelayKindAnyTls, snapshot.relayKind)
            assertTrue(snapshot.relayEnabled)
            assertEquals("anytls.example.com", snapshot.relayServer)
            assertEquals(443, snapshot.relayServerPort)
            assertTrue("AnyTLS carries UDP traffic", snapshot.relayUdpEnabled)

            val profile = profileStore.load(DefaultRelayProfileId)
            assertEquals(RelayKindAnyTls, profile?.kind)
            assertTrue(profile?.udpEnabled ?: false)

            val credentials = credentialStore.load(DefaultRelayProfileId)
            assertEquals(passwordFixture, credentials?.anyTlsPassword)
        }

    @Test
    fun `saving an incomplete anytls editor is a no-op`() =
        runTest {
            val settings = FakeAppSettingsRepository()
            val viewModel = viewModel(FakeRelayProfileStore(), FakeRelayCredentialStore(), settings)

            // Only server filled; port and password missing — toProfile() returns null.
            viewModel.onFieldChanged(AnyTlsEditorField.SERVER, "anytls.example.com")
            viewModel.onSave()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.saved)
            assertFalse(settings.snapshot().relayEnabled)
        }
}

private class FakeRelayProfileStore : RelayProfileStore {
    private val profiles = mutableMapOf<String, RelayProfileRecord>()

    override suspend fun load(profileId: String): RelayProfileRecord? = profiles[profileId]

    override suspend fun list(): List<RelayProfileRecord> = profiles.values.toList()

    override suspend fun save(profile: RelayProfileRecord) {
        profiles[profile.id] = profile
    }

    override suspend fun clear(profileId: String) {
        profiles.remove(profileId)
    }
}

private class FakeRelayCredentialStore : RelayCredentialStore {
    private val credentials = mutableMapOf<String, RelayCredentialRecord>()

    override suspend fun load(profileId: String): RelayCredentialRecord? = credentials[profileId]

    override suspend fun save(credentials: RelayCredentialRecord) {
        this.credentials[credentials.profileId] = credentials
    }

    override suspend fun clear(profileId: String) {
        credentials.remove(profileId)
    }
}

private class FakeAppSettingsRepository : AppSettingsRepository {
    private val state = MutableStateFlow(AppSettingsSerializer.defaultValue)

    override val settings: Flow<AppSettings> = state.asStateFlow()

    override suspend fun snapshot(): AppSettings = settings.first()

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
