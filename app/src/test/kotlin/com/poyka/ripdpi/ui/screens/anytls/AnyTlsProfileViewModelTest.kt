package com.poyka.ripdpi.ui.screens.anytls

import app.cash.turbine.test
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.fakes.FakeAppSettingsRepository
import com.poyka.ripdpi.data.fakes.FakeRelayCredentialStore
import com.poyka.ripdpi.data.fakes.FakeRelayProfileStore
import com.poyka.ripdpi.proxyimport.RelayProfileActivator
import com.poyka.ripdpi.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    ): AnyTlsProfileViewModel =
        AnyTlsProfileViewModel(
            RelayProfileActivator(
                com.poyka.ripdpi.proxyimport.TestDirectRelayProfileMutationCoordinator(
                    profileStore,
                    credentialStore,
                    settings,
                ),
            ),
        )

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
            viewModel.savedEvents.test {
                viewModel.onSave()
                advanceUntilIdle()
                awaitItem()
                viewModel.onSave()
                advanceUntilIdle()
                expectNoEvents()
            }
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

            assertFalse(settings.snapshot().relayEnabled)
        }
}
