package com.poyka.ripdpi.ui.screens.ssh

import app.cash.turbine.test
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindSsh
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Verifies the dedicated SSH editor's save path: a complete editor activates the
 * native relay (TCP-only) through [RelayProfileActivator] and flips `saved`, and
 * an incomplete editor is a no-op.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SshProfileViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // Named *Fixture so the no-secrets pre-commit hook excludes the line.
    private val passwordFixture = "ssh-vm-fixture-secret"

    private fun viewModel(
        profileStore: RelayProfileStore,
        credentialStore: RelayCredentialStore,
        settings: AppSettingsRepository,
    ): SshProfileViewModel = SshProfileViewModel(RelayProfileActivator(profileStore, credentialStore, settings))

    @Test
    fun `onSave activates the ssh relay with udp disabled and flips saved`() =
        runTest {
            val profileStore = FakeRelayProfileStore()
            val credentialStore = FakeRelayCredentialStore()
            val settings = FakeAppSettingsRepository()
            val viewModel = viewModel(profileStore, credentialStore, settings)

            viewModel.onFieldChanged(SshEditorField.SERVER, "ssh.example")
            viewModel.onFieldChanged(SshEditorField.SERVER_PORT, "22")
            viewModel.onFieldChanged(SshEditorField.USERNAME, "alice")
            viewModel.onFieldChanged(SshEditorField.PASSWORD, passwordFixture)
            viewModel.savedEvents.test {
                viewModel.onSave()
                advanceUntilIdle()
                awaitItem()
                expectNoEvents()
            }
            assertFalse(viewModel.uiState.value.saving)

            val snapshot = settings.snapshot()
            assertEquals(RelayKindSsh, snapshot.relayKind)
            assertTrue(snapshot.relayEnabled)
            assertEquals("ssh.example", snapshot.relayServer)
            assertEquals(22, snapshot.relayServerPort)
            assertFalse("SSH is direct-tcpip / TCP-only", snapshot.relayUdpEnabled)

            val profile = profileStore.load(DefaultRelayProfileId)
            assertEquals(RelayKindSsh, profile?.kind)
            assertFalse(profile?.udpEnabled ?: true)

            val credentials = credentialStore.load(DefaultRelayProfileId)
            assertEquals("alice", credentials?.sshUsername)
            assertEquals(passwordFixture, credentials?.sshPassword)
        }

    @Test
    fun `onSave is a no-op for an incomplete editor`() =
        runTest {
            val settings = FakeAppSettingsRepository()
            val viewModel = viewModel(FakeRelayProfileStore(), FakeRelayCredentialStore(), settings)

            viewModel.onFieldChanged(SshEditorField.SERVER, "ssh.example") // no port / username / secret
            viewModel.onSave()
            advanceUntilIdle()

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
