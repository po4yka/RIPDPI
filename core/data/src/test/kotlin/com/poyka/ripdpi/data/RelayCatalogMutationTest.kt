package com.poyka.ripdpi.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCatalogMutationTest : RoomProfileTest() {
    @Test
    fun `rename moves the profile and credentials and follows the active selection`() =
        runTest {
            val fixture = fixture()
            val coordinator = fixture.coordinator()
            coordinator.upsertRelay(
                coordinator.captureMutation(ProfileMutationOrigin.SavedEdit),
                RelayProfileRecord("old", RelayKindTrojan, server = "relay.example"),
                RelayCredentialRecord("old", trojanPassword = "fixture-secret"),
                enabled = true,
                select = true,
            )

            coordinator.renameRelay(coordinator.captureMutation(ProfileMutationOrigin.SavedEdit), "old", "new")

            assertNull(fixture.relayProfiles.load("old"))
            assertNull(fixture.relayCredentials.load("old"))
            assertEquals("relay.example", fixture.relayProfiles.load("new")?.server)
            assertEquals("fixture-secret", fixture.relayCredentials.load("new")?.trojanPassword)
            assertEquals("new", fixture.settings.snapshot().relayProfileId)
        }

    @Test
    fun `rename refuses a taken id and delete refuses the active or a chained profile`() =
        runTest {
            val fixture = fixture()
            val coordinator = fixture.coordinator()

            suspend fun save(
                profile: RelayProfileRecord,
                select: Boolean,
            ) = coordinator.upsertRelay(
                coordinator.captureMutation(ProfileMutationOrigin.SavedEdit),
                profile,
                RelayCredentialRecord(profile.id),
                enabled = select,
                select = select,
            )
            save(RelayProfileRecord("hop", RelayKindTrojan, server = "hop.example"), select = false)
            save(RelayProfileRecord("chain", RelayKindChainRelay, chainEntryProfileId = "hop"), select = false)
            save(RelayProfileRecord("active", RelayKindTrojan, server = "active.example"), select = true)

            suspend fun failure(block: suspend () -> Unit) = runCatching { block() }.exceptionOrNull()

            suspend fun edit() = coordinator.captureMutation(ProfileMutationOrigin.ExplicitDeletion)
            assertTrue(failure { coordinator.renameRelay(edit(), "hop", "active") } is RelayProfileIdTakenException)
            assertTrue(failure { coordinator.renameRelay(edit(), "hop", "other") } is RelayProfileInUseException)
            assertTrue(failure { coordinator.deleteRelay(edit(), "hop") } is RelayProfileInUseException)
            assertTrue(failure { coordinator.deleteRelay(edit(), "active") } is RelayProfileInUseException)
            assertEquals("hop.example", fixture.relayProfiles.load("hop")?.server)

            coordinator.deleteRelay(edit(), "chain")
            coordinator.deleteRelay(edit(), "hop")

            assertNull(fixture.relayProfiles.load("chain"))
            assertNull(fixture.relayProfiles.load("hop"))
            assertNull(fixture.relayCredentials.load("hop"))
        }
}
