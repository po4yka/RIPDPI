package com.poyka.ripdpi.data.xray

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class XrayMetadataTimestampTest {
    @Test
    fun `missing persisted timestamp is unknown and stable across repeated metadata loads`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val encoded = """{"profileId":"legacy","revision":"recorded","serverAddress":"fixture.example"}"""
            context
                .getSharedPreferences("xray_profile_metadata", Context.MODE_PRIVATE)
                .edit()
                .putString("xray-profile:legacy", encoded)
                .commit()
            val store = SharedPreferencesXrayProfileMetadataStore(context)
            val first = checkNotNull(store.load("legacy"))
            assertEquals(0L, first.updatedAtEpochMillis)
            assertEquals(first, store.load("legacy"))
            assertEquals(listOf(first), store.list())
        }

    @Test
    fun `default metadata remains unknown but import records the actual owning timestamp`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val store = SharedPreferencesXrayProfileMetadataStore(context)
            val unknown = XrayProfileMetadataRecord(profileId = "unknown", revision = "recorded")
            assertEquals(0L, unknown.updatedAtEpochMillis)
            store.save(unknown)
            assertEquals(unknown, store.load("unknown"))
            val before = System.currentTimeMillis()
            val profile =
                XrayProfile(
                    name = "Fixture",
                    outbound =
                        XrayProfile.Outbound(
                            serverAddress = "fixture.example",
                            serverPort = 443,
                            uuid = "fixture",
                            security = XrayProfile.Security.TLS,
                            network = XrayProfile.Network.TCP,
                        ),
                )
            val imported = profile.toXrayProfileRecordPair("imported", "revision").metadata
            val after = System.currentTimeMillis()
            assertTrue(imported.updatedAtEpochMillis in before..after)
            store.save(imported)
            assertEquals(imported, store.load("imported"))
        }
}
