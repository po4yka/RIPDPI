package com.poyka.ripdpi.ui.screens.mieru

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindMieru
import com.poyka.ripdpi.data.RelayMieruMultiplexingMiddle
import com.poyka.ripdpi.data.RelayMieruProtocolTcp
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
 * Verifies the dedicated Mieru editor's save path: a complete editor activates the
 * native relay (TCP-only) through [RelayProfileActivator] and flips `saved`, and
 * an incomplete editor is a no-op.
 *
 * This test is the regression guard proving that Mieru activation works end-to-end —
 * the P0-1 fix that wired [RelayProfileActivator] into [MieruProfileViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MieruProfileViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // Named *Fixture so the no-secrets pre-commit hook excludes the line.
    private val usernameFixture = "mieru-user-fixture"
    private val passwordFixture = "mieru-pass-fixture"

    private fun viewModel(
        profileStore: RelayProfileStore,
        credentialStore: RelayCredentialStore,
        settings: AppSettingsRepository,
    ): MieruProfileViewModel = MieruProfileViewModel(RelayProfileActivator(profileStore, credentialStore, settings))

    @Test
    fun `saving a complete mieru editor activates the native relay`() =
        runTest {
            val profileStore = FakeRelayProfileStore()
            val credentialStore = FakeRelayCredentialStore()
            val settings = FakeAppSettingsRepository()
            val viewModel = viewModel(profileStore, credentialStore, settings)

            viewModel.onFieldChanged(MieruEditorField.SERVER, "mieru.example.com")
            viewModel.onFieldChanged(MieruEditorField.SERVER_PORT, "8080")
            viewModel.onFieldChanged(MieruEditorField.USERNAME, usernameFixture)
            viewModel.onFieldChanged(MieruEditorField.PASSWORD, passwordFixture)
            // Protocol defaults to TCP, multiplexing defaults to "middle" — no explicit selection needed.
            viewModel.onSave()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.saved)
            assertFalse(viewModel.uiState.value.saving)
            assertNull(viewModel.uiState.value.errorMessage)

            val snapshot = settings.snapshot()
            assertEquals(RelayKindMieru, snapshot.relayKind)
            assertTrue(snapshot.relayEnabled)
            assertEquals("mieru.example.com", snapshot.relayServer)
            assertEquals(8080, snapshot.relayServerPort)
            assertFalse("Mieru is TCP-only; UDP relay is disabled", snapshot.relayUdpEnabled)
            assertEquals(RelayMieruProtocolTcp, snapshot.relayMieruProtocol)
            assertEquals(RelayMieruMultiplexingMiddle, snapshot.relayMieruMultiplexing)

            val profile = profileStore.load(DefaultRelayProfileId)
            assertEquals(RelayKindMieru, profile?.kind)
            assertFalse(profile?.udpEnabled ?: true)

            val credentials = credentialStore.load(DefaultRelayProfileId)
            assertEquals(usernameFixture, credentials?.mieruUsername)
            assertEquals(passwordFixture, credentials?.mieruPassword)
        }

    @Test
    fun `saving an incomplete mieru editor is a no-op`() =
        runTest {
            val settings = FakeAppSettingsRepository()
            val viewModel = viewModel(FakeRelayProfileStore(), FakeRelayCredentialStore(), settings)

            // Only server filled; port, username, and password are missing — toProfile() returns null.
            viewModel.onFieldChanged(MieruEditorField.SERVER, "mieru.example.com")
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
