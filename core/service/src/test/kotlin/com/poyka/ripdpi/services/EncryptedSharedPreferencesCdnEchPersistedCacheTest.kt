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
import org.junit.Assert.assertTrue
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
    private lateinit var currentPrefs: SharedPreferences
    private lateinit var legacyPrefs: SharedPreferences

    @Before
    fun setUp() {
        prefs =
            RuntimeEnvironment
                .getApplication()
                .getSharedPreferences(TEST_PREFS_NAME, Context.MODE_PRIVATE)
        currentPrefs =
            RuntimeEnvironment
                .getApplication()
                .getSharedPreferences(TEST_CURRENT_PREFS_NAME, Context.MODE_PRIVATE)
        legacyPrefs =
            RuntimeEnvironment
                .getApplication()
                .getSharedPreferences(TEST_LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        currentPrefs.edit().clear().commit()
        legacyPrefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
        currentPrefs.edit().clear().commit()
        legacyPrefs.edit().clear().commit()
    }

    @Test
    fun `legacy storage identifiers remain stable`() {
        assertEquals("ripdpi_cdn_ech_cache", CDN_ECH_CACHE_PREFS_NAME)
        assertEquals("config_bytes_b64", CDN_ECH_CACHE_CONFIG_BYTES_B64_KEY)
        assertEquals("fetched_at_unix_ms", CDN_ECH_CACHE_FETCHED_AT_KEY)
    }

    @Test
    fun `current storage identifiers remain stable`() {
        assertEquals("ripdpi_cdn_ech_cache_v2", CDN_ECH_CACHE_CURRENT_PREFS_NAME)
        assertEquals("legacy_migration_complete_v1", CDN_ECH_CACHE_LEGACY_MIGRATION_COMPLETE_KEY)
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

    @Test
    fun `valid current entry wins without loading legacy`() {
        val currentEntry = PersistedEchEntry(configBytes = byteArrayOf(1, 2), fetchedAtUnixMs = 42L)
        CdnEchPreferencesCodec(currentPrefs).save(currentEntry)
        var legacyLoads = 0
        val coordinator =
            coordinator {
                legacyLoads += 1
                error("legacy should not load")
            }

        assertEntryEquals(currentEntry, coordinator.load())
        assertEquals(0, legacyLoads)
        assertFalse(currentPrefs.contains(CDN_ECH_CACHE_LEGACY_MIGRATION_COMPLETE_KEY))
    }

    @Test
    fun `legacy entry migrates synchronously and a fresh coordinator uses current storage`() {
        val legacyEntry = PersistedEchEntry(configBytes = byteArrayOf(0, 1, 127, -128, -1), fetchedAtUnixMs = 1234L)
        CdnEchPreferencesCodec(legacyPrefs).save(legacyEntry)
        var legacyLoads = 0
        val coordinator =
            coordinator {
                legacyLoads += 1
                CdnEchPreferencesCodec(legacyPrefs).load()
            }

        assertEntryEquals(legacyEntry, coordinator.load())
        assertEquals(1, legacyLoads)
        assertEntryEquals(legacyEntry, CdnEchCurrentPreferencesCodec(currentPrefs).load())
        assertTrue(currentPrefs.getBoolean(CDN_ECH_CACHE_LEGACY_MIGRATION_COMPLETE_KEY, false))

        val freshCoordinator = coordinator { error("fresh coordinator should use current storage") }
        assertEntryEquals(legacyEntry, freshCoordinator.load())
        assertEquals(1, legacyLoads)
    }

    @Test
    fun `migration marker suppresses missing current entry without loading legacy`() {
        currentPrefs.edit().putBoolean(CDN_ECH_CACHE_LEGACY_MIGRATION_COMPLETE_KEY, true).commit()
        var legacyLoads = 0
        val coordinator =
            coordinator {
                legacyLoads += 1
                error("legacy should not load")
            }

        assertNull(coordinator.load())
        assertEquals(0, legacyLoads)
    }

    @Test
    fun `legacy load failure returns no entry and leaves migration retryable`() {
        var legacyLoads = 0
        val coordinator =
            coordinator {
                legacyLoads += 1
                error("legacy unavailable")
            }

        assertNull(coordinator.load())
        assertEquals(1, legacyLoads)
        assertFalse(currentPrefs.contains(CDN_ECH_CACHE_LEGACY_MIGRATION_COMPLETE_KEY))
    }

    @Test
    fun `ordinary save writes exact current entry and never loads legacy`() {
        val legacyEntry = PersistedEchEntry(configBytes = byteArrayOf(9), fetchedAtUnixMs = 1L)
        CdnEchPreferencesCodec(legacyPrefs).save(legacyEntry)
        var legacyLoads = 0
        val coordinator =
            coordinator {
                legacyLoads += 1
                CdnEchPreferencesCodec(legacyPrefs).load()
            }
        val currentEntry = PersistedEchEntry(configBytes = ByteArray(128) { it.toByte() }, fetchedAtUnixMs = 99L)

        coordinator.save(currentEntry)

        assertEquals(0, legacyLoads)
        assertEquals(99L, currentPrefs.getLong(CDN_ECH_CACHE_FETCHED_AT_KEY, -1L))
        val stored = requireNotNull(currentPrefs.getString(CDN_ECH_CACHE_CONFIG_BYTES_B64_KEY, null))
        assertFalse(stored.contains('\n'))
        assertFalse(stored.contains('\r'))
        assertArrayEquals(currentEntry.configBytes, Base64.decode(stored, Base64.NO_WRAP))
        assertTrue(currentPrefs.getBoolean(CDN_ECH_CACHE_LEGACY_MIGRATION_COMPLETE_KEY, false))
        assertEntryEquals(legacyEntry, CdnEchPreferencesCodec(legacyPrefs).load())
    }

    @Test
    fun `clear removes current entry and marker prevents legacy resurrection`() {
        val legacyEntry = PersistedEchEntry(configBytes = byteArrayOf(9), fetchedAtUnixMs = 1L)
        CdnEchPreferencesCodec(legacyPrefs).save(legacyEntry)
        var legacyLoads = 0
        val coordinator =
            coordinator {
                legacyLoads += 1
                CdnEchPreferencesCodec(legacyPrefs).load()
            }
        coordinator.save(PersistedEchEntry(configBytes = byteArrayOf(1, 2, 3), fetchedAtUnixMs = 42L))

        coordinator.clear()

        assertFalse(currentPrefs.contains(CDN_ECH_CACHE_CONFIG_BYTES_B64_KEY))
        assertFalse(currentPrefs.contains(CDN_ECH_CACHE_FETCHED_AT_KEY))
        assertTrue(currentPrefs.getBoolean(CDN_ECH_CACHE_LEGACY_MIGRATION_COMPLETE_KEY, false))
        assertNull(coordinator.load())
        assertEquals(0, legacyLoads)
        assertEntryEquals(legacyEntry, CdnEchPreferencesCodec(legacyPrefs).load())
    }

    private fun coordinator(loadLegacy: () -> PersistedEchEntry?): CdnEchPreferencesMigrationCoordinator =
        CdnEchPreferencesMigrationCoordinator(
            currentCodec = CdnEchCurrentPreferencesCodec(currentPrefs),
            loadLegacy = loadLegacy,
        )

    private fun assertEntryEquals(
        expected: PersistedEchEntry,
        actual: PersistedEchEntry?,
    ) {
        assertEquals(expected.fetchedAtUnixMs, actual?.fetchedAtUnixMs)
        assertArrayEquals(expected.configBytes, actual?.configBytes)
    }

    private companion object {
        const val TEST_PREFS_NAME = "cdn_ech_cache_codec_test"
        const val TEST_CURRENT_PREFS_NAME = "cdn_ech_cache_current_test"
        const val TEST_LEGACY_PREFS_NAME = "cdn_ech_cache_legacy_test"
    }
}
