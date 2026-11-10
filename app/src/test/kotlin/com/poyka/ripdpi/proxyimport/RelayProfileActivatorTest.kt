package com.poyka.ripdpi.proxyimport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.mapRelayProfile
import com.poyka.ripdpi.data.testPauseAuthority
import com.poyka.ripdpi.data.testing.RoomProfileFixture
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.XrayProfileMetadataRecord
import com.poyka.ripdpi.data.xray.XrayProfileSecretRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RelayProfileActivatorTest {
    private val databases = mutableListOf<RoomProfileFixture>()

    @After
    fun closeStorage() = databases.forEach { it.close() }

    @Test
    fun `native activation replaces the selected provider without deleting Xray`() =
        runTest {
            val storage = storage()
            val xrayMetadata =
                XrayProfileMetadataRecord(
                    profileId = "saved-xray",
                    revision = "saved",
                    serverAddress = "prior.example",
                    security = XrayProfileMetadataRecord.SecurityTls,
                    hasTls = true,
                    tlsServerName = "prior.example",
                )
            val xraySecrets =
                XrayProfileSecretRecord(
                    "saved-xray",
                    revision = "saved",
                    uuid = "11111111-2222-3333-4444-555555555555",
                )
            storage.xrayMetadata.save(xrayMetadata)
            storage.xraySecrets.save(xraySecrets)
            storage.xraySelection.update(XrayProviderSelectionRecord("xray", "saved-xray"))
            val activator = RelayProfileActivator(storage.coordinator)

            assertTrue(
                activator.activate(activator.captureMutation(ProfileMutationOrigin.ExplicitActivation), profile()),
            )

            assertEquals(VpnProviderKind.Native, storage.xraySelection.current().kind)
            assertEquals("", storage.xraySelection.current().activeProfileId)
            assertEquals(xrayMetadata, storage.xrayMetadata.load("saved-xray"))
            assertEquals(xraySecrets, storage.xraySecrets.load("saved-xray"))
            assertEquals("relay.example", storage.relayProfiles.load(DefaultRelayProfileId)?.server)
            assertEquals("fixture-password", storage.relayCredentials.load(DefaultRelayProfileId)?.trojanPassword)
            assertTrue(storage.settings.snapshot().relayEnabled)
        }

    @Test
    fun `store-only relay update preserves the selected Xray provider`() =
        runTest {
            val storage = storage()
            val selection = XrayProviderSelectionRecord("xray", "saved-xray")
            storage.xraySelection.update(selection)
            val mapped = checkNotNull(mapRelayProfile(profile(), "stored-relay"))

            storage.coordinator.upsertRelay(
                storage.coordinator.captureMutation(ProfileMutationOrigin.SavedEdit),
                profile = mapped.profile,
                credentials = mapped.credentials,
                enabled = false,
                select = false,
            )

            assertEquals(selection, storage.xraySelection.current())
            assertNotNull(storage.relayProfiles.load("stored-relay"))
        }

    @Test
    fun `failed native activation preserves the prior provider and relay state`() =
        runTest {
            val storage = storage()
            val selection = XrayProviderSelectionRecord("xray", "saved-xray")
            storage.xraySelection.update(selection)
            val settings = storage.settings.snapshot()
            storage.beforeSeal = { namespace, _ ->
                if (namespace == "app_settings") error("fixture write failure")
            }
            val activator = RelayProfileActivator(storage.coordinator)

            assertTrue(
                runCatching {
                    activator.activate(activator.captureMutation(ProfileMutationOrigin.ExplicitActivation), profile())
                }.isFailure,
            )

            assertEquals(selection, storage.xraySelection.current())
            assertEquals(settings, storage.settings.snapshot())
            assertEquals(null, storage.relayProfiles.load(DefaultRelayProfileId))
            assertEquals(null, storage.relayCredentials.load(DefaultRelayProfileId))
        }

    private suspend fun storage(): RoomProfileFixture =
        RoomProfileFixture(ApplicationProvider.getApplicationContext<Context>(), testPauseAuthority()).also {
            databases += it
            it.initialize()
        }

    private fun profile() = ProxyProfile.Trojan("native", "Native", "group", "relay.example", 443, "fixture-password")
}
