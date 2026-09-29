package com.poyka.ripdpi.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WarpStoresTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `clearing endpoint removes legacy fallback across store recreation`() =
        runTest {
            val store = SharedPreferencesWarpEndpointStore(context)
            store.clearAll()
            val home = WarpEndpointCacheEntry(networkScopeKey = "wifi:home", port = 2408, updatedAtEpochMillis = 123L)
            val other = home.copy(networkScopeKey = "wifi:other")
            saveLegacyEndpoint(home)
            saveLegacyEndpoint(other)
            store.save(home.copy(port = 500))
            assertEquals(500, store.load(DefaultWarpProfileId, "wifi:home")?.port)

            store.clear(DefaultWarpProfileId, "wifi:home")

            val reopened = SharedPreferencesWarpEndpointStore(context)
            assertNull(reopened.load(DefaultWarpProfileId, "wifi:home"))
            assertEquals(other, reopened.load(DefaultWarpProfileId, "wifi:other"))
        }

    @Test
    fun `clearing default profile removes legacy scopes and preserves other profiles`() =
        runTest {
            val store = SharedPreferencesWarpEndpointStore(context)
            store.clearAll()
            val home = WarpEndpointCacheEntry(networkScopeKey = "wifi:home", port = 2408, updatedAtEpochMillis = 123L)
            saveLegacyEndpoint(home)
            saveLegacyEndpoint(home.copy(networkScopeKey = GlobalWarpEndpointScopeKey))
            store.save(home.copy(networkScopeKey = "cellular"))
            val corporate = home.copy(profileId = "corp")
            store.save(corporate)

            store.clearProfile(DefaultWarpProfileId)

            val reopened = SharedPreferencesWarpEndpointStore(context)
            assertNull(reopened.load(DefaultWarpProfileId, "wifi:home"))
            assertNull(reopened.load(DefaultWarpProfileId, GlobalWarpEndpointScopeKey))
            assertNull(reopened.load(DefaultWarpProfileId, "cellular"))
            assertEquals(corporate, reopened.load("corp", "wifi:home"))
        }

    @Test
    fun `clearing another profile preserves default legacy endpoint`() =
        runTest {
            val store = SharedPreferencesWarpEndpointStore(context)
            store.clearAll()
            val home = WarpEndpointCacheEntry(networkScopeKey = "wifi:home", port = 2408, updatedAtEpochMillis = 123L)
            saveLegacyEndpoint(home)
            store.save(home.copy(profileId = "corp"))

            store.clearProfile("corp")

            val reopened = SharedPreferencesWarpEndpointStore(context)
            assertNull(reopened.load("corp", "wifi:home"))
            assertEquals(home, reopened.load(DefaultWarpProfileId, "wifi:home"))
        }

    @Test
    fun `clearing legacy scope cannot delete another profile with the same key shape`() =
        runTest {
            val store = SharedPreferencesWarpEndpointStore(context)
            store.clearAll()
            val corporate =
                WarpEndpointCacheEntry(
                    profileId = "corp",
                    networkScopeKey = "wifi:home",
                    port = 2408,
                    updatedAtEpochMillis = 123L,
                )
            store.save(corporate)

            store.clear(DefaultWarpProfileId, "corp:wifi:home")
            store.clearProfile(DefaultWarpProfileId)

            assertEquals(corporate, SharedPreferencesWarpEndpointStore(context).load("corp", "wifi:home"))
        }

    @Test
    fun `clearing modern key shape preserves legacy network owned by default profile`() =
        runTest {
            val store = SharedPreferencesWarpEndpointStore(context)
            store.clearAll()
            val legacy =
                WarpEndpointCacheEntry(
                    networkScopeKey = "corp:wifi:home",
                    port = 2408,
                    updatedAtEpochMillis = 123L,
                )
            saveLegacyEndpoint(legacy)

            store.clear("corp", "wifi:home")
            assertEquals(
                legacy,
                SharedPreferencesWarpEndpointStore(context).load(DefaultWarpProfileId, "corp:wifi:home"),
            )
            store.clearProfile("corp")
            assertEquals(
                legacy,
                SharedPreferencesWarpEndpointStore(context).load(DefaultWarpProfileId, "corp:wifi:home"),
            )
        }

    private fun saveLegacyEndpoint(entry: WarpEndpointCacheEntry) {
        context
            .getSharedPreferences("warp_endpoint_cache", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "endpoint:${entry.networkScopeKey}",
                RipDpiJson.encodeToString(WarpEndpointCacheEntry.serializer(), entry),
            ).commit()
    }

    @Test
    fun `profile store persists active profile independently from profile records`() =
        runTest {
            val store = SharedPreferencesWarpProfileStore(context)

            store.clearAll()
            store.save(
                WarpProfile(
                    id = "consumer",
                    accountKind = WarpAccountKindConsumerFree,
                    displayName = "Consumer",
                    setupState = WarpSetupStateProvisioned,
                ),
            )
            store.save(
                WarpProfile(
                    id = "corp",
                    accountKind = WarpAccountKindZeroTrust,
                    displayName = "Corp",
                    zeroTrustOrg = "acme",
                    setupState = WarpSetupStateProvisioned,
                ),
            )
            store.setActiveProfileId("corp")

            assertEquals("corp", store.activeProfileId())
            assertEquals(listOf("consumer", "corp"), store.loadAll().map(WarpProfile::id))
        }

    @Test
    fun `endpoint store scopes cached entries by profile id and network`() =
        runTest {
            val store = SharedPreferencesWarpEndpointStore(context)

            store.clearAll()
            store.save(
                WarpEndpointCacheEntry(
                    profileId = "consumer",
                    networkScopeKey = "wifi:home",
                    host = "engage.cloudflareclient.com",
                    port = 2408,
                ),
            )
            store.save(
                WarpEndpointCacheEntry(
                    profileId = "corp",
                    networkScopeKey = "wifi:home",
                    host = "zero-trust-client.cloudflareclient.com",
                    port = 2408,
                ),
            )

            assertEquals("engage.cloudflareclient.com", store.load("consumer", "wifi:home")?.host)
            assertEquals("zero-trust-client.cloudflareclient.com", store.load("corp", "wifi:home")?.host)

            store.clearProfile("consumer")

            assertNull(store.load("consumer", "wifi:home"))
            assertEquals("zero-trust-client.cloudflareclient.com", store.load("corp", "wifi:home")?.host)
        }
}
