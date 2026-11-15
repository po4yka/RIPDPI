package com.poyka.ripdpi.proxyimport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.mapRelayProfile
import com.poyka.ripdpi.data.testPauseAuthority
import com.poyka.ripdpi.data.testing.RoomProfileFixture
import com.poyka.ripdpi.data.uri.ProxyUriCodec
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.XrayProfileMetadataRecord
import com.poyka.ripdpi.data.xray.XrayProfileSecretRecord
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RelayProfileActivatorTest {
    private val databases = mutableListOf<RoomProfileFixture>()

    @Test
    fun `typed TUIC activates and retains stored credentials and settings`() =
        runTest {
            val storage = storage()
            val profile =
                ProxyUriCodec.parse(
                    "tuic://11111111-2222-3333-4444-555555555555:fixture@203.0.113.7:8443/" +
                        "?sni=tls.example&alpn=tuic%2Ch3&congestion_control=cubic#Node",
                ) as ProxyProfile.Tuic
            val activator = RelayProfileActivator(storage.coordinator)
            assertTrue(activator.activate(activator.captureMutation(ProfileMutationOrigin.ExplicitActivation), profile))
            val saved = requireNotNull(storage.relayProfiles.load(DefaultRelayProfileId))
            val credentials = requireNotNull(storage.relayCredentials.load(DefaultRelayProfileId))

            assertEquals("tuic_v5", saved.kind)
            assertEquals("tls.example", saved.serverName)
            assertEquals("cubic", saved.tuicCongestionControl)
            assertEquals("tuic,h3", saved.tuicAlpn)
            assertEquals(profile.uuid, credentials.tuicUuid)
            assertEquals("fixture", credentials.tuicPassword)
        }

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
    fun `confirmed imports keep both profiles without changing the prior selection`() =
        runTest {
            val storage = storage()
            val previous = RelayProfileRecord(DefaultRelayProfileId, RelayKindTrojan, server = "prior.example")
            val previousCredentials = RelayCredentialRecord(DefaultRelayProfileId, trojanPassword = "fixture-prior")
            storage.relayProfiles.save(previous)
            storage.relayCredentials.save(previousCredentials)
            storage.settings.update {
                setRelayProfileId(previous.id)
                setRelayEnabled(true)
            }
            val originalSettings = storage.settings.snapshot()
            val selection = XrayProviderSelectionRecord("xray", "saved-xray")
            storage.xraySelection.update(selection)
            val importer = RelayProfileActivator(storage.coordinator)
            val first = profile().copy(id = "first", displayName = "First relay")
            val second = profile().copy(id = "second", server = "second.example", password = "fixture-second")
            assertTrue(importer.saveImport(first))
            assertTrue(importer.saveImport(second))

            storage.beforeSeal = { namespace, _ ->
                if (namespace == "relay_credentials") error("fixture write failure")
            }
            val failed = profile().copy(id = "failed")
            assertTrue(runCatching { importer.saveImport(failed) }.isFailure)

            storage.reopen().use { restarted ->
                restarted.initialize()
                assertEquals(previous, restarted.relayProfiles.load(previous.id))
                assertEquals(previousCredentials, restarted.relayCredentials.load(previous.id))
                assertEquals("First relay", restarted.relayProfiles.load(first.id)?.operatorName)
                assertEquals(first.server, restarted.relayProfiles.load(first.id)?.server)
                assertEquals(first.password, restarted.relayCredentials.load(first.id)?.trojanPassword)
                assertEquals(second.server, restarted.relayProfiles.load(second.id)?.server)
                assertEquals(second.password, restarted.relayCredentials.load(second.id)?.trojanPassword)
                assertEquals(originalSettings, restarted.settings.snapshot())
                assertEquals(selection, restarted.xraySelection.current())
                val importedGroups = restarted.groups.list()
                assertEquals(setOf(first.id, second.id), importedGroups.map { it.members.single().id }.toSet())
                importedGroups.forEach { group ->
                    assertEquals(group.id, group.members.single().groupId)
                }
                assertNull(restarted.relayProfiles.load(failed.id))
                assertNull(restarted.relayCredentials.load(failed.id))
            }
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
