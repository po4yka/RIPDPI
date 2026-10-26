package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiProxyUIPreferences
import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.UnreadableCredentialException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RuntimeConfigurationCatalogCaptureTest {
    @Test
    fun `a chain hop whose credentials no longer decrypt is marked instead of failing the capture`() =
        runTest {
            val profiles = TestRelayProfileStore()
            profiles.profiles["relay-lost"] = RelayProfileRecord(id = "relay-lost", kind = RelayKindTrojan)
            val credentials =
                object : RelayCredentialStore by TestRelayCredentialStore() {
                    override suspend fun load(profileId: String): RelayCredentialRecord? =
                        throw UnreadableCredentialException(javax.crypto.AEADBadTagException("fixture"))
                }
            val warpCredentials = FakeWarpCredentialStore()
            val capture =
                RuntimeConfigurationCatalogCapture(
                    TestProfileMutationCoordinator(
                        TestAppSettingsRepository(AppSettingsSerializer.defaultValue),
                        com.poyka.ripdpi.data.SharedPreferencesWarpProfileStore(
                            org.robolectric.RuntimeEnvironment.getApplication(),
                        ),
                        warpCredentials,
                        FakeWarpEndpointStore(),
                    ),
                    profiles,
                    credentials,
                    warpCredentials,
                    FakeDurableXrayProfileStore(),
                    FakeSelectionStore(),
                    object : SelectorRelayRuntimeProfileResolver {
                        override suspend fun resolve(): SelectedSelectorRelay? = null
                    },
                )

            val captured =
                capture.capture(
                    Mode.Proxy,
                    AppSettingsSerializer.defaultValue,
                    RipDpiProxyUIPreferences(
                        relay = RipDpiRelayConfig(enabled = true, kind = RelayKindTrojan, profileId = "relay-lost"),
                    ),
                    awg = null,
                )

            assertTrue("unreadable-credentials:relay-lost" in captured.material)
        }
}
