package com.poyka.ripdpi.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
