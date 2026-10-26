package com.poyka.ripdpi.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMutationEditIsolationTest {
    @Test
    fun `catalog observer reads stores off the collector thread`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val coordinator = fixture.coordinator()
            val io = AppCoroutineDispatchers(Dispatchers.IO, Dispatchers.IO, Dispatchers.IO)
            val reader = ProfileUtilityCatalogReader(coordinator, fixture.stores, fixture.pauseAuthority, io)
            val collectorThread = Thread.currentThread()

            reader.observe().first()

            assertTrue(fixture.xrayMetadata.listThreads.isNotEmpty())
            assertTrue(fixture.xrayMetadata.listThreads.none { it == collectorThread })
        }

    @Test
    fun `relay editor commit keeps a settings write made after the editor snapshot`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val coordinator = fixture.coordinator()
            val profile = RelayProfileRecord(id = "relay-edit", kind = RelayKindTrojan, server = "edited.example")
            val credentials = RelayCredentialRecord(profileId = profile.id, trojanPassword = "secret")
            fixture.settings.update { setRelayServerName("old.sni") }
            val base = fixture.settings.snapshot()
            val after =
                base
                    .toBuilder()
                    .setRelayServer("edited.example")
                    .setRelayServerName("")
                    .build()
            fixture.settings.update { setProxyPort(base.proxyPort + 1) }

            coordinator.upsertRelayEdit(
                coordinator.captureMutation(ProfileMutationOrigin.SavedEdit),
                profile = profile,
                credentials = credentials,
                enabled = true,
                settingsBaseImage = base,
                settingsAfterImage = after,
                expectedState = ExpectedRelayProfileState(null, null),
            )

            val saved = fixture.settings.snapshot()
            assertEquals("edited.example", saved.relayServer)
            assertEquals("", saved.relayServerName)
            assertEquals("concurrent settings write was reverted", base.proxyPort + 1, saved.proxyPort)
        }
}
