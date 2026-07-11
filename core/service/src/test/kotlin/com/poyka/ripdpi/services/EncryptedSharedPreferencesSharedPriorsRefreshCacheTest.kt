package com.poyka.ripdpi.services

import android.content.Context
import android.content.SharedPreferences
import com.poyka.ripdpi.data.SharedPriorsRefreshState
import org.junit.After
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
class EncryptedSharedPreferencesSharedPriorsRefreshCacheTest {
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
        assertEquals("ripdpi_shared_priors_refresh", SHARED_PRIORS_REFRESH_PREFS_NAME)
        assertEquals("last_refresh_unix_ms", SHARED_PRIORS_REFRESH_LAST_REFRESH_KEY)
        assertEquals("last_modified_header", SHARED_PRIORS_REFRESH_LAST_MODIFIED_KEY)
    }

    @Test
    fun `current storage identifiers remain stable`() {
        assertEquals("ripdpi_shared_priors_refresh_v2", SHARED_PRIORS_REFRESH_CURRENT_PREFS_NAME)
        assertEquals("legacy_migration_complete_v1", SHARED_PRIORS_REFRESH_LEGACY_MIGRATION_COMPLETE_KEY)
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

    @Test
    fun `valid current state wins without loading legacy`() {
        val currentState = SharedPriorsRefreshState(lastRefreshUnixMs = 42L, lastModifiedHeader = "current")
        currentPrefs
            .edit()
            .putLong(SHARED_PRIORS_REFRESH_LAST_REFRESH_KEY, currentState.lastRefreshUnixMs)
            .putString(SHARED_PRIORS_REFRESH_LAST_MODIFIED_KEY, currentState.lastModifiedHeader)
            .commit()
        var legacyLoads = 0
        val coordinator =
            coordinator {
                legacyLoads += 1
                error("legacy should not load")
            }

        assertEquals(currentState, coordinator.load())
        assertEquals(0, legacyLoads)
        assertFalse(currentPrefs.contains(SHARED_PRIORS_REFRESH_LEGACY_MIGRATION_COMPLETE_KEY))
    }

    @Test
    fun `legacy state migrates synchronously and a fresh coordinator uses current storage`() {
        val legacyState = SharedPriorsRefreshState(lastRefreshUnixMs = 1234L, lastModifiedHeader = "etag-v1")
        SharedPriorsRefreshPreferencesCodec(legacyPrefs).save(legacyState)
        var legacyLoads = 0
        val coordinator =
            coordinator {
                legacyLoads += 1
                SharedPriorsRefreshPreferencesCodec(legacyPrefs).load()
            }

        assertEquals(legacyState, coordinator.load())
        assertEquals(1, legacyLoads)
        assertEquals(legacyState, SharedPriorsRefreshCurrentPreferencesCodec(currentPrefs).load())
        assertTrue(currentPrefs.getBoolean(SHARED_PRIORS_REFRESH_LEGACY_MIGRATION_COMPLETE_KEY, false))

        val freshCoordinator = coordinator { error("fresh coordinator should use current storage") }
        assertEquals(legacyState, freshCoordinator.load())
        assertEquals(1, legacyLoads)
    }

    @Test
    fun `migration marker suppresses missing current state without loading legacy`() {
        currentPrefs.edit().putBoolean(SHARED_PRIORS_REFRESH_LEGACY_MIGRATION_COMPLETE_KEY, true).commit()
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
    fun `legacy load failure returns no state and leaves migration retryable`() {
        var legacyLoads = 0
        val coordinator =
            coordinator {
                legacyLoads += 1
                error("legacy unavailable")
            }

        assertNull(coordinator.load())
        assertEquals(1, legacyLoads)
        assertFalse(currentPrefs.contains(SHARED_PRIORS_REFRESH_LEGACY_MIGRATION_COMPLETE_KEY))
    }

    @Test
    fun `ordinary save writes exact current state and never loads legacy`() {
        currentPrefs.edit().putString(SHARED_PRIORS_REFRESH_LAST_MODIFIED_KEY, "stale").commit()
        val legacyState = SharedPriorsRefreshState(lastRefreshUnixMs = 1L, lastModifiedHeader = "legacy")
        SharedPriorsRefreshPreferencesCodec(legacyPrefs).save(legacyState)
        var legacyLoads = 0
        val coordinator =
            coordinator {
                legacyLoads += 1
                SharedPriorsRefreshPreferencesCodec(legacyPrefs).load()
            }
        val currentState = SharedPriorsRefreshState(lastRefreshUnixMs = 99L, lastModifiedHeader = null)

        coordinator.save(currentState)

        assertEquals(0, legacyLoads)
        assertEquals(99L, currentPrefs.getLong(SHARED_PRIORS_REFRESH_LAST_REFRESH_KEY, -1L))
        assertFalse(currentPrefs.contains(SHARED_PRIORS_REFRESH_LAST_MODIFIED_KEY))
        assertTrue(currentPrefs.getBoolean(SHARED_PRIORS_REFRESH_LEGACY_MIGRATION_COMPLETE_KEY, false))
        assertEquals(legacyState, SharedPriorsRefreshPreferencesCodec(legacyPrefs).load())
    }

    private fun coordinator(
        loadLegacy: () -> SharedPriorsRefreshState?,
    ): SharedPriorsRefreshPreferencesMigrationCoordinator =
        SharedPriorsRefreshPreferencesMigrationCoordinator(
            currentCodec = SharedPriorsRefreshCurrentPreferencesCodec(currentPrefs),
            loadLegacy = loadLegacy,
        )

    private companion object {
        const val TEST_PREFS_NAME = "shared_priors_refresh_codec_test"
        const val TEST_CURRENT_PREFS_NAME = "shared_priors_refresh_current_test"
        const val TEST_LEGACY_PREFS_NAME = "shared_priors_refresh_legacy_test"
    }
}
