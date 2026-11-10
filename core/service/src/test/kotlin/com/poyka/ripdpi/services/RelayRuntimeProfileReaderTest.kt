package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.testPauseAuthority
import com.poyka.ripdpi.data.testing.RoomProfileFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RelayRuntimeProfileReaderTest {
    @Test
    fun `runtime reads one relay generation while a mutation waits between the two loads`() =
        runTest {
            RoomProfileFixture(RuntimeEnvironment.getApplication(), testPauseAuthority()).use { fixture ->
                fixture.initialize()
                val coordinator = fixture.coordinator
                val original = RelayProfileRecord(id = "relay", kind = RelayKindTrojan, server = "old.example")
                val originalCredentials =
                    RelayCredentialRecord(profileId = original.id, trojanPassword = readerCredential(1))
                coordinator.upsertRelay(
                    coordinator.captureMutation(ProfileMutationOrigin.Bootstrap),
                    original,
                    originalCredentials,
                    enabled = true,
                    select = true,
                )
                val preparation = coordinator.captureMutation(ProfileMutationOrigin.SavedEdit)
                val profileLoaded = CompletableDeferred<Unit>()
                val releaseProfile = CompletableDeferred<Unit>()
                val profiles =
                    object : RelayProfileStore by fixture.relayProfiles {
                        override suspend fun load(profileId: String): RelayProfileRecord? {
                            val profile = fixture.relayProfiles.load(profileId)
                            profileLoaded.complete(Unit)
                            releaseProfile.await()
                            return profile
                        }
                    }
                val reader = RelayRuntimeProfileReader(profiles, fixture.relayCredentials, coordinator)
                val read = async { reader.read(original.id) }
                profileLoaded.await()
                val replacement = original.copy(server = "new.example")
                val replacementCredentials = originalCredentials.copy(trojanPassword = readerCredential(2))
                val mutation =
                    async {
                        coordinator.upsertRelay(
                            preparation,
                            replacement,
                            replacementCredentials,
                            enabled = true,
                            select = true,
                        )
                    }
                try {
                    // A real deadline lets the old unlocked path commit before the credential read.
                    withContext(Dispatchers.IO) {
                        withTimeoutOrNull(MutationWaitMillis) { mutation.await() }
                    }
                } finally {
                    releaseProfile.complete(Unit)
                }

                val observed = read.await()
                mutation.await()

                assertEquals(original.server, observed.profile?.server)
                assertEquals(originalCredentials.trojanPassword, observed.credentials?.trojanPassword)
                assertEquals(replacement, fixture.relayProfiles.load(original.id))
                assertEquals(replacementCredentials, fixture.relayCredentials.load(original.id))
            }
        }

    private fun readerCredential(slot: Int): String = "relay-reader-fixture-$slot"

    private companion object {
        const val MutationWaitMillis = 1_000L
    }
}
