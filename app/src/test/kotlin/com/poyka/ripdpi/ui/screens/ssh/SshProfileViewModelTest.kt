package com.poyka.ripdpi.ui.screens.ssh

import app.cash.turbine.test
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigRelayArtifactRepository
import com.poyka.ripdpi.core.SshHostKeyProbeFailure
import com.poyka.ripdpi.core.SshHostKeyProbeResult
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindSsh
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelaySshAuthTypePrivateKey
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.proxyimport.RelayProfileActivator
import com.poyka.ripdpi.services.SshHostKeyObserver
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
    private val observedFingerprintFixture = "SHA256:n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg"
    private val observedFingerprintUrlSafeFixture = "SHA256:n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg="
    private val mixedAlphabetFingerprintFixture = "SHA256:n4bQgYhMfWWaL-qgxVrQFaO/TxsrC4Is0V1sFbDwCgg"
    private val otherFingerprintFixture = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

    private fun observed(fingerprint: String) = SshHostKeyProbeResult.Observed(fingerprint, "ssh-ed25519")

    private fun viewModel(
        profileStore: RelayProfileStore,
        credentialStore: RelayCredentialStore,
        settings: AppSettingsRepository,
        hostKeyObserver: SshHostKeyObserver = FakeSshHostKeyObserver(),
    ): SshProfileViewModel =
        SshProfileViewModel(
            RelayProfileActivator(
                com.poyka.ripdpi.proxyimport.TestDirectRelayProfileMutationCoordinator(
                    profileStore,
                    credentialStore,
                    settings,
                ),
            ),
            ConfigRelayArtifactRepository(
                settings,
                profileStore,
                credentialStore,
                profileMutations =
                    com.poyka.ripdpi.proxyimport.TestDirectRelayProfileMutationCoordinator(
                        profileStore,
                        credentialStore,
                        settings,
                    ),
            ),
            hostKeyObserver,
        )

    private fun hostKeyViewModel(observer: SshHostKeyObserver): SshProfileViewModel =
        viewModel(FakeRelayProfileStore(), FakeRelayCredentialStore(), FakeAppSettingsRepository(), observer)
            .apply {
                onFieldChanged(SshEditorField.SERVER, "ssh.example")
                onFieldChanged(SshEditorField.SERVER_PORT, "2222")
            }

    private fun SshProfileViewModel.pinnedFingerprint(): String =
        uiState.value.editor.rawText(SshEditorField.HOST_KEY_FINGERPRINT)

    @Test
    fun `fetched host key waits for confirmation and confirm pins it`() =
        runTest {
            val viewModel = hostKeyViewModel(FakeSshHostKeyObserver(observed(observedFingerprintFixture)))

            viewModel.onFetchHostKey()
            advanceUntilIdle()

            assertEquals(
                SshHostKeyFetchState.PendingConfirmation(
                    fingerprint = observedFingerprintFixture,
                    algorithm = "ssh-ed25519",
                    differsFromPinned = false,
                ),
                viewModel.uiState.value.hostKeyFetch,
            )
            assertEquals("", viewModel.pinnedFingerprint())

            viewModel.onHostKeyTrustConfirmed()

            assertEquals(observedFingerprintFixture, viewModel.pinnedFingerprint())
            assertEquals(SshHostKeyFetchState.Idle, viewModel.uiState.value.hostKeyFetch)
        }

    @Test
    fun `dismissing the trust step leaves the pinned fingerprint unchanged`() =
        runTest {
            val viewModel = hostKeyViewModel(FakeSshHostKeyObserver(observed(observedFingerprintFixture)))

            viewModel.onFetchHostKey()
            advanceUntilIdle()
            viewModel.onHostKeyTrustDismissed()
            viewModel.onHostKeyTrustConfirmed()

            assertEquals("", viewModel.pinnedFingerprint())
            assertEquals(SshHostKeyFetchState.Idle, viewModel.uiState.value.hostKeyFetch)
        }

    @Test
    fun `failed fetch shows the mapped error and keeps the field`() =
        runTest {
            val viewModel =
                hostKeyViewModel(
                    FakeSshHostKeyObserver(SshHostKeyProbeResult.Failed(SshHostKeyProbeFailure.Timeout)),
                )

            viewModel.onFetchHostKey()
            advanceUntilIdle()

            assertEquals(
                SshHostKeyFetchState.Failed(R.string.ssh_host_key_fetch_error_timeout),
                viewModel.uiState.value.hostKeyFetch,
            )
            assertEquals("", viewModel.pinnedFingerprint())
        }

    @Test
    fun `a key that differs from the pinned fingerprint is flagged`() =
        runTest {
            val viewModel = hostKeyViewModel(FakeSshHostKeyObserver(observed(observedFingerprintFixture)))
            viewModel.onFieldChanged(SshEditorField.HOST_KEY_FINGERPRINT, otherFingerprintFixture)

            viewModel.onFetchHostKey()
            advanceUntilIdle()

            val pending = viewModel.uiState.value.hostKeyFetch as SshHostKeyFetchState.PendingConfirmation
            assertTrue(pending.differsFromPinned)
            assertEquals(otherFingerprintFixture, viewModel.pinnedFingerprint())
        }

    @Test
    fun `the same key pinned in url-safe base64 is not flagged`() =
        runTest {
            val viewModel = hostKeyViewModel(FakeSshHostKeyObserver(observed(observedFingerprintFixture)))
            viewModel.onFieldChanged(SshEditorField.HOST_KEY_FINGERPRINT, observedFingerprintUrlSafeFixture)

            viewModel.onFetchHostKey()
            advanceUntilIdle()

            val pending = viewModel.uiState.value.hostKeyFetch as SshHostKeyFetchState.PendingConfirmation
            assertFalse(pending.differsFromPinned)
        }

    @Test
    fun `editing the endpoint during a fetch drops the late result`() =
        runTest {
            val gate = CompletableDeferred<SshHostKeyProbeResult>()
            val viewModel = hostKeyViewModel(FakeSshHostKeyObserver(gates = listOf(gate)))

            viewModel.onFetchHostKey()
            advanceUntilIdle()
            assertEquals(SshHostKeyFetchState.InProgress, viewModel.uiState.value.hostKeyFetch)

            viewModel.onFieldChanged(SshEditorField.SERVER, "other.example")
            gate.complete(observed(observedFingerprintFixture))
            advanceUntilIdle()

            assertEquals(SshHostKeyFetchState.Idle, viewModel.uiState.value.hostKeyFetch)
            assertEquals("", viewModel.pinnedFingerprint())
        }

    @Test
    fun `a re-tap ignores the late result of the first fetch`() =
        runTest {
            val first = CompletableDeferred<SshHostKeyProbeResult>()
            val second = CompletableDeferred<SshHostKeyProbeResult>()
            val viewModel = hostKeyViewModel(FakeSshHostKeyObserver(gates = listOf(first, second)))

            viewModel.onFetchHostKey()
            advanceUntilIdle()
            viewModel.onFetchHostKey()
            advanceUntilIdle()
            second.complete(observed(otherFingerprintFixture))
            advanceUntilIdle()
            first.complete(observed(observedFingerprintFixture))
            advanceUntilIdle()

            val pending = viewModel.uiState.value.hostKeyFetch as SshHostKeyFetchState.PendingConfirmation
            assertEquals(otherFingerprintFixture, pending.fingerprint)
        }

    @Test
    fun `a pin that mixes base64 alphabets is flagged as different`() =
        runTest {
            val viewModel = hostKeyViewModel(FakeSshHostKeyObserver(observed(observedFingerprintFixture)))
            viewModel.onFieldChanged(SshEditorField.HOST_KEY_FINGERPRINT, mixedAlphabetFingerprintFixture)

            viewModel.onFetchHostKey()
            advanceUntilIdle()

            val pending = viewModel.uiState.value.hostKeyFetch as SshHostKeyFetchState.PendingConfirmation
            assertTrue(pending.differsFromPinned)
        }

    @Test
    fun `fetch is unavailable without a valid port`() =
        runTest {
            val viewModel = hostKeyViewModel(FakeSshHostKeyObserver(observed(observedFingerprintFixture)))
            viewModel.onFieldChanged(SshEditorField.SERVER_PORT, "99999")

            assertFalse(viewModel.uiState.value.canFetchHostKey)
            viewModel.onFetchHostKey()
            advanceUntilIdle()

            assertEquals(SshHostKeyFetchState.Idle, viewModel.uiState.value.hostKeyFetch)
        }

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

    @Test
    fun `editing imported SSH keeps its id and private key`() =
        runTest {
            val profiles = FakeRelayProfileStore()
            val credentials = FakeRelayCredentialStore()
            val original =
                RelayProfileRecord(
                    id = "ssh-imported",
                    kind = RelayKindSsh,
                    server = "before.example",
                    serverPort = 22,
                    sshAuthType = RelaySshAuthTypePrivateKey,
                    sshHostKeyFingerprint = "SHA256:original",
                    sshStrictHostKey = true,
                )
            profiles.save(original)
            credentials.save(
                RelayCredentialRecord(
                    profileId = original.id,
                    sshUsername = "alice",
                    sshPrivateKey = "key-fixture",
                    sshPrivateKeyPassphrase = "passphrase-fixture",
                ),
            )
            val viewModel = viewModel(profiles, credentials, FakeAppSettingsRepository())
            viewModel.loadProfile(original.id)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.editing)
            viewModel.onFieldChanged(SshEditorField.DISPLAY_NAME, "another-id")
            viewModel.onFieldChanged(SshEditorField.SERVER, "after.example")
            viewModel.savedEvents.test {
                viewModel.onSave()
                advanceUntilIdle()
                awaitItem()
            }
            assertEquals("after.example", profiles.load(original.id)?.server)
            assertEquals(null, profiles.load("another-id"))
            assertEquals(original.sshHostKeyFingerprint, profiles.load(original.id)?.sshHostKeyFingerprint)
            assertTrue(profiles.load(original.id)?.sshStrictHostKey == true)
            assertEquals("key-fixture", credentials.load(original.id)?.sshPrivateKey)
            assertEquals("passphrase-fixture", credentials.load(original.id)?.sshPrivateKeyPassphrase)
        }

    @Test
    fun `missing SSH edit target cannot create a new profile`() =
        runTest {
            val profiles = FakeRelayProfileStore()
            val viewModel = viewModel(profiles, FakeRelayCredentialStore(), FakeAppSettingsRepository())
            viewModel.loadProfile("missing")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.notFound)
            viewModel.onFieldChanged(SshEditorField.SERVER, "ssh.example")
            viewModel.onFieldChanged(SshEditorField.SERVER_PORT, "22")
            viewModel.onFieldChanged(SshEditorField.USERNAME, "alice")
            viewModel.onFieldChanged(SshEditorField.PASSWORD, passwordFixture)
            viewModel.onSave()
            advanceUntilIdle()
            assertTrue(profiles.list().isEmpty())
        }

    @Test
    fun `edit route without a profile id shows not found instead of the new form`() =
        runTest {
            val viewModel = viewModel(FakeRelayProfileStore(), FakeRelayCredentialStore(), FakeAppSettingsRepository())
            viewModel.loadProfile("")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.notFound)
        }

    @Test
    fun `a corrupt SSH profile keeps the load error instead of not found`() =
        runTest {
            val corruptProfiles =
                object : RelayProfileStore by FakeRelayProfileStore() {
                    override suspend fun load(profileId: String): RelayProfileRecord? =
                        throw IllegalArgumentException("corrupt fixture record")
                }
            val viewModel = viewModel(corruptProfiles, FakeRelayCredentialStore(), FakeAppSettingsRepository())
            viewModel.loadProfile("ssh-1")
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.notFound)
            assertEquals(R.string.relay_editor_activation_failed, viewModel.uiState.value.errorMessage)
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

/**
 * Answers each call with the next of [gates], or with [result] when none is left. A gated call
 * ignores cancellation, like a native probe that finishes after its caller has gone.
 */
private class FakeSshHostKeyObserver(
    private val result: SshHostKeyProbeResult = SshHostKeyProbeResult.Failed(SshHostKeyProbeFailure.InternalFailure),
    gates: List<CompletableDeferred<SshHostKeyProbeResult>> = emptyList(),
) : SshHostKeyObserver {
    private val pendingGates = ArrayDeque(gates)

    override suspend fun observe(
        server: String,
        port: Int,
    ): SshHostKeyProbeResult {
        val gate = pendingGates.removeFirstOrNull() ?: return result
        return withContext(NonCancellable) { gate.await() }
    }
}
