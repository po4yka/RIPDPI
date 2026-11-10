package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.fakes.FakeAppSettingsRepository
import com.poyka.ripdpi.data.fakes.FakeRelayCredentialStore
import com.poyka.ripdpi.data.fakes.FakeRelayProfileStore
import com.poyka.ripdpi.ui.screens.proxyimport.ProfileImportConfirmViewModel
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SshImportAdmissionTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `invalid and unpinned SSH imports leave durable state unchanged`() =
        runTest {
            val valid =
                ProxyProfile.Ssh(
                    "ssh",
                    "SSH",
                    "",
                    "ssh.example",
                    22,
                    "alice",
                    password = "fixture",
                    hostKeyFingerprint = "SHA256:n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg",
                )
            for (profile in listOf(
                valid.copy(hostKeyFingerprint = null),
                valid.copy(hostKeyFingerprint = "SHA256:invalid"),
                valid.copy(password = null),
                valid.copy(serverPort = 65536),
            )) {
                val groups = FakeProxyGroupRepository()
                val profiles = FakeRelayProfileStore()
                val credentials = FakeRelayCredentialStore()
                val settings = FakeAppSettingsRepository()
                val previous =
                    RelayProfileRecord(id = DefaultRelayProfileId, server = "previous.example")
                val previousPasswordFixture = "previous"
                val previousCredentials =
                    RelayCredentialRecord(profileId = DefaultRelayProfileId, sshPassword = previousPasswordFixture)
                profiles.save(previous)
                credentials.save(previousCredentials)
                settings.update {
                    setRelayEnabled(true)
                    setRelayServer(previous.server)
                }
                val before = settings.snapshot()
                val viewModel =
                    ProfileImportConfirmViewModel(
                        repository = groups,
                        relayActivator =
                            RelayProfileActivator(
                                TestDirectRelayProfileMutationCoordinator(profiles, credentials, settings),
                            ),
                        profileMutations =
                            com.poyka.ripdpi.data
                                .testMutationPreparationSource(),
                    )

                viewModel.setProfile(profile)
                viewModel.confirm()
                advanceUntilIdle()

                assertEquals(R.string.import_profile_confirm_error, viewModel.uiState.value.errorRes)
                assertEquals(before, settings.snapshot())
                assertTrue(groups.list().isEmpty())
                assertEquals(previous, profiles.load(DefaultRelayProfileId))
                assertEquals(previousCredentials, credentials.load(DefaultRelayProfileId))
            }
        }
}
