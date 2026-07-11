package com.poyka.ripdpi.services

import android.content.Context
import android.content.SharedPreferences
import com.poyka.ripdpi.data.SharedPriorsRefreshState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EncryptedSharedPreferencesSharedPriorsRefreshCacheTest {
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs =
            RuntimeEnvironment
                .getApplication()
                .getSharedPreferences(TEST_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `legacy storage identifiers remain stable`() {
        assertEquals("ripdpi_shared_priors_refresh", SHARED_PRIORS_REFRESH_PREFS_NAME)
        assertEquals("last_refresh_unix_ms", SHARED_PRIORS_REFRESH_LAST_REFRESH_KEY)
        assertEquals("last_modified_header", SHARED_PRIORS_REFRESH_LAST_MODIFIED_KEY)
    }

    @Test
    fun `empty preferences load as no state`() {
        assertNull(SharedPriorsRefreshPreferencesCodec(prefs).load())
    }

    @Test
    fun `missing or negative timestamp loads as no state even with header`() {
        prefs.edit().putString(SHARED_PRIORS_REFRESH_LAST_MODIFIED_KEY, "etag").commit()
        assertNull(SharedPriorsRefreshPreferencesCodec(prefs).load())

        prefs.edit().putLong(SHARED_PRIORS_REFRESH_LAST_REFRESH_KEY, -1L).commit()
        assertNull(SharedPriorsRefreshPreferencesCodec(prefs).load())
    }

    @Test
    fun `zero timestamp is valid with a null header`() {
        prefs.edit().putLong(SHARED_PRIORS_REFRESH_LAST_REFRESH_KEY, 0L).commit()

        assertEquals(
            SharedPriorsRefreshState(lastRefreshUnixMs = 0L, lastModifiedHeader = null),
            SharedPriorsRefreshPreferencesCodec(prefs).load(),
        )
    }

    @Test
    fun `positive timestamp and header survive a fresh codec`() {
        SharedPriorsRefreshPreferencesCodec(prefs).save(
            SharedPriorsRefreshState(lastRefreshUnixMs = 1234L, lastModifiedHeader = "etag-v1"),
        )

        assertEquals(
            SharedPriorsRefreshState(lastRefreshUnixMs = 1234L, lastModifiedHeader = "etag-v1"),
            SharedPriorsRefreshPreferencesCodec(prefs).load(),
        )
    }

    @Test
    fun `saving a null header removes the prior header`() {
        val codec = SharedPriorsRefreshPreferencesCodec(prefs)
        codec.save(SharedPriorsRefreshState(lastRefreshUnixMs = 1L, lastModifiedHeader = "etag-v1"))
        codec.save(SharedPriorsRefreshState(lastRefreshUnixMs = 2L, lastModifiedHeader = null))

        assertFalse(prefs.contains(SHARED_PRIORS_REFRESH_LAST_MODIFIED_KEY))
        assertEquals(
            SharedPriorsRefreshState(lastRefreshUnixMs = 2L, lastModifiedHeader = null),
            SharedPriorsRefreshPreferencesCodec(prefs).load(),
        )
    }

    private companion object {
        const val TEST_PREFS_NAME = "shared_priors_refresh_codec_test"
    }
}
