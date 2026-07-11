package com.poyka.ripdpi.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.poyka.ripdpi.data.PersistedEchEntry
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
class EncryptedSharedPreferencesCdnEchPersistedCacheTest {
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
        assertEquals("ripdpi_cdn_ech_cache", CDN_ECH_CACHE_PREFS_NAME)
        assertEquals("config_bytes_b64", CDN_ECH_CACHE_CONFIG_BYTES_B64_KEY)
        assertEquals("fetched_at_unix_ms", CDN_ECH_CACHE_FETCHED_AT_KEY)
    }

    @Test
    fun `empty preferences load as no entry`() {
        assertNull(CdnEchPreferencesCodec(prefs).load())
    }

    @Test
    fun `missing config or timestamp loads as no entry`() {
        prefs.edit().putLong(CDN_ECH_CACHE_FETCHED_AT_KEY, 42L).commit()
        assertNull(CdnEchPreferencesCodec(prefs).load())

        prefs
            .edit()
            .clear()
            .putString(CDN_ECH_CACHE_CONFIG_BYTES_B64_KEY, Base64.encodeToString(byteArrayOf(1), Base64.NO_WRAP))
            .commit()
        assertNull(CdnEchPreferencesCodec(prefs).load())
    }

    @Test
    fun `negative timestamp loads as no entry`() {
        prefs
            .edit()
            .putString(CDN_ECH_CACHE_CONFIG_BYTES_B64_KEY, Base64.encodeToString(byteArrayOf(1), Base64.NO_WRAP))
            .putLong(CDN_ECH_CACHE_FETCHED_AT_KEY, -1L)
            .commit()

        assertNull(CdnEchPreferencesCodec(prefs).load())
    }

    @Test
    fun `arbitrary binary entry survives a fresh codec`() {
        val bytes = byteArrayOf(0, 1, 127, -128, -1)
        CdnEchPreferencesCodec(prefs).save(PersistedEchEntry(configBytes = bytes, fetchedAtUnixMs = 1234L))

        val loaded = CdnEchPreferencesCodec(prefs).load()
        assertEquals(1234L, loaded?.fetchedAtUnixMs)
        assertArrayEquals(bytes, loaded?.configBytes)
    }

    @Test
    fun `saved bytes use unwrapped Base64`() {
        val bytes = ByteArray(128) { index -> index.toByte() }
        CdnEchPreferencesCodec(prefs).save(PersistedEchEntry(configBytes = bytes, fetchedAtUnixMs = 1L))

        val stored = requireNotNull(prefs.getString(CDN_ECH_CACHE_CONFIG_BYTES_B64_KEY, null))
        assertFalse(stored.contains('\n'))
        assertFalse(stored.contains('\r'))
        assertArrayEquals(bytes, Base64.decode(stored, Base64.NO_WRAP))
    }

    @Test
    fun `malformed Base64 loads as no entry`() {
        prefs
            .edit()
            .putString(CDN_ECH_CACHE_CONFIG_BYTES_B64_KEY, "A")
            .putLong(CDN_ECH_CACHE_FETCHED_AT_KEY, 1L)
            .commit()

        assertNull(CdnEchPreferencesCodec(prefs).load())
    }

    @Test
    fun `clear removes both stored fields`() {
        val codec = CdnEchPreferencesCodec(prefs)
        codec.save(PersistedEchEntry(configBytes = byteArrayOf(1, 2, 3), fetchedAtUnixMs = 42L))

        codec.clear()

        assertFalse(prefs.contains(CDN_ECH_CACHE_CONFIG_BYTES_B64_KEY))
        assertFalse(prefs.contains(CDN_ECH_CACHE_FETCHED_AT_KEY))
        assertNull(CdnEchPreferencesCodec(prefs).load())
    }

    private companion object {
        const val TEST_PREFS_NAME = "cdn_ech_cache_codec_test"
    }
}
