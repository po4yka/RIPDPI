package com.poyka.ripdpi.data.persistence

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.data.DefaultWarpProfileId
import com.poyka.ripdpi.data.UnreadableCredentialException
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import com.poyka.ripdpi.data.resolveAppSettingsStoreFile
import com.poyka.ripdpi.data.testPauseAuthority
import com.poyka.ripdpi.data.xray.XrayProfileMetadataRecord
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LegacyProfileImporterTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearFixtureSources() {
        listOf("warp_endpoint_cache", "relay_credentials_secure", "xray_profile_metadata").forEach { name ->
            assertTrue(
                context
                    .getSharedPreferences(name, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit(),
            )
        }
        resolveAppSettingsStoreFile(context).delete()
    }

    @Test
    fun `corrupt settings abort import without replacing the source`() =
        runTest {
            val file = resolveAppSettingsStoreFile(context)
            val directory = checkNotNull(file.parentFile)
            check(directory.mkdirs() || directory.isDirectory)
            val corrupt = byteArrayOf(0x0a)
            file.writeBytes(corrupt)

            val failure =
                runCatching {
                    LegacyProfileImporter(context, testPauseAuthority(initialize = false)).read()
                }.exceptionOrNull()

            assertTrue(failure is CorruptionException)
            assertArrayEquals(corrupt, file.readBytes())
        }

    @Test
    fun `an orphan unreadable credential blocks import instead of disappearing`() =
        runTest {
            val preferences = context.getSharedPreferences("relay_credentials_secure", Context.MODE_PRIVATE)
            assertTrue(preferences.edit().putString("relay-credentials:orphan", "AA==").commit())

            val failure =
                runCatching {
                    LegacyProfileImporter(context, testPauseAuthority(initialize = false)).read()
                }.exceptionOrNull()

            assertTrue(failure is UnreadableCredentialException)
            assertEquals("AA==", preferences.getString("relay-credentials:orphan", null))
        }

    @Test
    fun `endpoint import prefers current default entry and preserves another profile`() =
        runTest {
            val preferences = context.getSharedPreferences("warp_endpoint_cache", Context.MODE_PRIVATE)
            val legacy = WarpEndpointCacheEntry(networkScopeKey = "wifi:home", port = 2408, updatedAtEpochMillis = 1)
            val current = legacy.copy(port = 500)
            val corporate = legacy.copy(profileId = "corp", port = 1701)
            assertTrue(
                preferences
                    .edit()
                    .putString(
                        "endpoint:wifi:home",
                        RipDpiJson.encodeToString(WarpEndpointCacheEntry.serializer(), legacy),
                    ).putString(
                        "endpoint:$DefaultWarpProfileId:wifi:home",
                        RipDpiJson.encodeToString(WarpEndpointCacheEntry.serializer(), current),
                    ).putString(
                        "endpoint:corp:wifi:home",
                        RipDpiJson.encodeToString(WarpEndpointCacheEntry.serializer(), corporate),
                    ).commit(),
            )
            val authority = testPauseAuthority(initialize = false)

            val snapshot = LegacyProfileImporter(context, authority).read()

            assertEquals(current, snapshot.warpEndpoints.single { it.profileId == DefaultWarpProfileId })
            assertEquals(corporate, snapshot.warpEndpoints.single { it.profileId == "corp" })
            assertEquals(3, preferences.all.size)
            assertFalse(authority.isInitialized())
        }

    @Test
    fun `metadata with a different key identity is rejected before migration`() =
        runTest {
            val preferences = context.getSharedPreferences("xray_profile_metadata", Context.MODE_PRIVATE)
            val encoded =
                RipDpiJson.encodeToString(
                    XrayProfileMetadataRecord.serializer(),
                    XrayProfileMetadataRecord(profileId = "other"),
                )
            assertTrue(preferences.edit().putString("xray-profile:expected", encoded).commit())

            val failure =
                runCatching {
                    LegacyProfileImporter(context, testPauseAuthority(initialize = false)).read()
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(encoded, preferences.getString("xray-profile:expected", null))
        }
}
