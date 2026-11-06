package com.poyka.ripdpi.data.boot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.ProfileMutationOrigin
import com.poyka.ripdpi.data.RoomProfileTest
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSelectionRoomTest : RoomProfileTest() {
    @Test
    fun `active AWG selection survives database reopen and stays outside direct boot hints`() =
        runTest {
            val fixture = fixture()
            val coordinator = fixture.coordinator()
            coordinator.upsertAwg(
                coordinator.captureMutation(ProfileMutationOrigin.SavedEdit),
                AwgProfileEntity("awg-profile-42", "AWG", "{}", 1),
                AwgSecrets("private-key"),
            )
            coordinator.activateStandaloneAwg(
                coordinator.captureMutation(ProfileMutationOrigin.ExplicitActivation),
                "awg-profile-42",
            )
            fixture.reopen()
            assertEquals("awg-profile-42", fixture.profileSelection.snapshot().awgProfileId)
            val bootPreferences =
                ApplicationProvider
                    .getApplicationContext<Context>()
                    .createDeviceProtectedStorageContext()
                    .getSharedPreferences("ripdpi_boot_session_state", Context.MODE_PRIVATE)
            assertFalse(bootPreferences.contains("boot-session-active-awg-profile-id"))

            assertTrue(
                fixture.coordinator().clearStandaloneAwg(fixture.pauseAuthority.reserveStop(), "awg-profile-42"),
            )
            fixture.reopen()
            assertNull(fixture.profileSelection.snapshot().awgProfileId)
        }
}
