package com.poyka.ripdpi.data.awg

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round-trip tests for [SharedPreferencesAwgProviderSelectionStore] — the durable,
 * secret-free standalone-AmneziaWG selection signal that `:core:service` reads at
 * VPN startup (and on a kill/boot resume) to decide whether to branch onto the
 * AmneziaWG runtime. The store must default to inactive and persist the active
 * profile-id pointer across reads.
 */
@RunWith(RobolectricTestRunner::class)
class AwgProviderSelectionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        // The store uses a fixed prefs file name; clear it so each test starts clean.
        context
            .getSharedPreferences("awg_provider_selection", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private val store = SharedPreferencesAwgProviderSelectionStore(context)

    @Test
    fun `current defaults to inactive before any selection is written`() =
        runBlocking {
            assertEquals(AwgSelectionRecord(), store.current())
            assertFalse(store.current().active)
        }

    @Test
    fun `update round-trips the active flag and profile id`() =
        runBlocking {
            store.update(AwgSelectionRecord(active = true, activeProfileId = "awg-1234"))

            assertEquals(AwgSelectionRecord(active = true, activeProfileId = "awg-1234"), store.current())
        }

    @Test
    fun `update clears the selection back to inactive`() =
        runBlocking {
            store.update(AwgSelectionRecord(active = true, activeProfileId = "awg-1234"))
            store.update(AwgSelectionRecord(active = false))

            val record = store.current()
            assertFalse(record.active)
            assertEquals("", record.activeProfileId)
        }

    @Test
    fun `current survives a fresh store instance over the same prefs`() =
        runBlocking {
            store.update(AwgSelectionRecord(active = true, activeProfileId = "awg-persist"))

            val reopened = SharedPreferencesAwgProviderSelectionStore(context)
            assertEquals("awg-persist", reopened.current().activeProfileId)
        }
}
