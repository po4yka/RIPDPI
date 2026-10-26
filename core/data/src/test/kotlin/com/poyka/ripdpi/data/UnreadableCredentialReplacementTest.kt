package com.poyka.ripdpi.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A sealed credential that no longer decrypts must never block the write that replaces it. */
@RunWith(RobolectricTestRunner::class)
class UnreadableCredentialReplacementTest {
    @Test
    fun `relay editor save replaces credentials that can no longer be decrypted`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val coordinator = fixture.coordinator()
            val profile = RelayProfileRecord(id = "relay-lost-key", kind = RelayKindTrojan, server = "relay.example")
            fixture.relayProfiles.save(profile)
            fixture.relayCredentials.unreadable += profile.id
            val replacement = RelayCredentialRecord(profileId = profile.id, trojanPassword = "re-entered-fixture")
            val base = fixture.settings.snapshot()

            coordinator.upsertRelayEdit(
                coordinator.captureMutation(ProfileMutationOrigin.SavedEdit),
                profile = profile,
                credentials = replacement,
                enabled = true,
                settingsBaseImage = base,
                settingsAfterImage = base,
                // The editor opened the unreadable secret as empty.
                expectedState = ExpectedRelayProfileState(profile, null),
            )

            assertEquals(replacement, fixture.relayCredentials.load(profile.id))
        }

    @Test
    fun `warp upsert replaces credentials that can no longer be decrypted`() =
        runTest {
            val fixture = ProfileMutationTestFixture()
            val coordinator = fixture.coordinator()
            val profile = WarpProfile(id = "warp-lost-key", displayName = "Warp")
            fixture.warpCredentials.unreadable += profile.id
            val replacement =
                WarpCredentials(profileId = profile.id, deviceId = "device", accessToken = "re-enrolled-fixture")

            coordinator.upsertWarp(
                coordinator.captureMutation(ProfileMutationOrigin.SavedEdit),
                profile,
                replacement,
                emptyList(),
                false,
                WarpScannerModeAutomatic,
            )

            assertEquals(replacement, fixture.warpCredentials.load(profile.id))
        }
}
