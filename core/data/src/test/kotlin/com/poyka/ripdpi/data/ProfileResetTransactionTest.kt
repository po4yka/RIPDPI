package com.poyka.ripdpi.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileResetTransactionTest : RoomProfileTest() {
    @Test
    fun `deleting the active AWG clears canonical selection and all profile parts`() =
        runTest {
            val fixture = fixture()
            saveAwg(fixture, "active")
            activateAwg(fixture, "active")
            fixture.coordinator().deleteAwg(
                fixture.coordinator().captureMutation(ProfileMutationOrigin.ExplicitDeletion),
                "active",
            )
            assertNull(fixture.profileSelection.snapshot().awgProfileId)
            assertNull(fixture.awgCredentials.load("active"))
            assertNull(withContext(Dispatchers.IO) { fixture.awgProfiles.getProfile("active") })
            fixture.reopen()
            assertNull(fixture.profileSelection.snapshot().awgProfileId)
        }

    @Test
    fun `deleting a different AWG preserves canonical selection`() =
        runTest {
            val fixture = fixture()
            saveAwg(fixture, "active")
            saveAwg(fixture, "other")
            activateAwg(fixture, "active")
            fixture.coordinator().deleteAwg(
                fixture.coordinator().captureMutation(ProfileMutationOrigin.ExplicitDeletion),
                "other",
            )
            assertEquals("active", fixture.profileSelection.snapshot().awgProfileId)
            assertNotNull(fixture.awgCredentials.load("active"))
            fixture.reopen()
            assertEquals("active", fixture.profileSelection.snapshot().awgProfileId)
        }

    @Test
    fun `failed reset rolls back profiles rules settings and marker`() =
        runTest {
            val fixture = fixture()
            saveAwg(fixture, "active")
            activateAwg(fixture, "active")
            fixture.settings.update { setProxyPort(18080) }
            val before = fixture.snapshot()
            fixture.failWrite("app_settings")
            var callbackRan = false
            assertTrue(runCatching { fixture.coordinator().runReset { callbackRan = true } }.isFailure)
            assertFalse(callbackRan)
            assertEquals(before, fixture.snapshot())
            assertEquals(DesiredRuntimeState.Stopped, fixture.pauseAuthority.snapshotAuthority().desired)
            fixture.clearFaults()
            fixture.reopen()
            assertEquals(before, fixture.snapshot())
            fixture.coordinator().runReset { callbackRan = true }
            assertTrue(callbackRan)
            assertEquals(AppSettingsSerializer.defaultValue, fixture.settings.snapshot())
            assertTrue(withContext(Dispatchers.IO) { fixture.awgProfiles.allProfiles().isEmpty() })
            assertNull(fixture.profileSelection.snapshot().awgProfileId)
            assertTrue(fixture.snapshot().rules.isEmpty())
        }

    @Test
    fun `failed first import keeps legacy source and retries without partial records`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val profile = RelayProfileRecord("legacy", RelayKindTrojan, server = "legacy.example")
            val prefs = context.getSharedPreferences("relay_profile_cache", Context.MODE_PRIVATE)
            check(
                prefs
                    .edit()
                    .putString(
                        "relay-profile:legacy",
                        RipDpiJson.encodeToString(RelayProfileRecord.serializer(), profile),
                    ).commit(),
            )
            val fixture = fixture()
            val before = fixture.snapshot()
            fixture.failWrite("system")
            assertTrue(runCatching { fixture.initialize() }.isFailure)
            assertEquals(before, fixture.snapshot())
            assertTrue(prefs.contains("relay-profile:legacy"))
            fixture.clearFaults()
            fixture.initialize()
            assertEquals(profile, fixture.relayProfiles.load("legacy"))
            val committed = fixture.snapshot()
            fixture.reopen()
            assertEquals(committed, fixture.snapshot())
            assertEquals(profile, fixture.relayProfiles.load("legacy"))
        }

    @Test
    fun `reset removes imported profiles and prevents legacy source resurrection`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val profile = RelayProfileRecord("legacy", RelayKindTrojan, server = "legacy.example")
            val encoded = RipDpiJson.encodeToString(RelayProfileRecord.serializer(), profile)
            val prefs = context.getSharedPreferences("relay_profile_cache", Context.MODE_PRIVATE)
            check(prefs.edit().putString("relay-profile:legacy", encoded).commit())
            val fixture = fixture()
            fixture.initialize()
            assertEquals(profile, fixture.relayProfiles.load("legacy"))
            check(prefs.edit().putString("relay-profile:legacy", encoded).commit())
            fixture.coordinator().runReset { }
            fixture.reopen()
            assertTrue(fixture.relayProfiles.list().isEmpty())
            assertFalse(prefs.contains("relay-profile:legacy"))
            assertEquals(AppSettingsSerializer.defaultValue, fixture.settings.snapshot())
        }

    @Test
    fun `corrupt source blocks ordinary access without mutation and explicit reset can discard it`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val file = resolveAppSettingsStoreFile(context)
            check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
            val corrupt = byteArrayOf(-1, -1, -1)
            file.writeBytes(corrupt)
            val fixture = fixture()
            val before = fixture.snapshot()
            repeat(2) { assertTrue(runCatching { fixture.settings.snapshot() }.isFailure) }
            assertArrayEquals(corrupt, file.readBytes())
            assertEquals(before, fixture.snapshot())
            fixture.coordinator().runReset { }
            assertEquals(AppSettingsSerializer.defaultValue, fixture.settings.snapshot())
            fixture.reopen()
            assertEquals(AppSettingsSerializer.defaultValue, fixture.settings.snapshot())
            assertFalse(file.exists())
        }

    private suspend fun saveAwg(
        fixture: ProfileMutationTestFixture,
        id: String,
    ) {
        fixture.coordinator().upsertAwg(
            fixture.coordinator().captureMutation(ProfileMutationOrigin.SavedEdit),
            AwgProfileEntity(id, id, "{}", 42L),
            AwgSecrets("private-$id"),
        )
    }

    private suspend fun activateAwg(
        fixture: ProfileMutationTestFixture,
        id: String,
    ) {
        fixture.coordinator().activateStandaloneAwg(
            fixture.coordinator().captureMutation(ProfileMutationOrigin.ExplicitActivation),
            id,
        )
        assertEquals(id, fixture.profileSelection.snapshot().awgProfileId)
    }
}
