@file:Suppress("DEPRECATION")

package com.poyka.ripdpi.services

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.poyka.ripdpi.data.CdnEchPersistedCache
import com.poyka.ripdpi.data.PersistedEchEntry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

internal const val CdnEchCachePrefsName = "ripdpi_cdn_ech_cache"
internal const val CdnEchCacheCurrentPrefsName = "ripdpi_cdn_ech_cache_v2"
internal const val CdnEchCacheConfigBytesB64Key = "config_bytes_b64"
internal const val CdnEchCacheFetchedAtKey = "fetched_at_unix_ms"
internal const val CdnEchCacheLegacyMigrationCompleteKey = "legacy_migration_complete_v1"

@Suppress("TopLevelPropertyNaming")
internal const val CDN_ECH_CACHE_PREFS_NAME = CdnEchCachePrefsName

@Suppress("TopLevelPropertyNaming")
internal const val CDN_ECH_CACHE_CURRENT_PREFS_NAME = CdnEchCacheCurrentPrefsName

@Suppress("TopLevelPropertyNaming")
internal const val CDN_ECH_CACHE_CONFIG_BYTES_B64_KEY = CdnEchCacheConfigBytesB64Key

@Suppress("TopLevelPropertyNaming")
internal const val CDN_ECH_CACHE_FETCHED_AT_KEY = CdnEchCacheFetchedAtKey

@Suppress("TopLevelPropertyNaming")
internal const val CDN_ECH_CACHE_LEGACY_MIGRATION_COMPLETE_KEY = CdnEchCacheLegacyMigrationCompleteKey

internal class CdnEchPreferencesCodec(
    private val prefs: SharedPreferences,
) {
    fun load(): PersistedEchEntry? {
        val configB64 = prefs.getString(CdnEchCacheConfigBytesB64Key, null)
        val fetchedAt = prefs.getLong(CdnEchCacheFetchedAtKey, -1L)
        return configB64
            ?.takeIf { fetchedAt >= 0L }
            ?.let { encoded ->
                runCatching {
                    android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
                }.getOrNull()
            }?.let { PersistedEchEntry(configBytes = it, fetchedAtUnixMs = fetchedAt) }
    }

    fun save(entry: PersistedEchEntry) {
        val configB64 = android.util.Base64.encodeToString(entry.configBytes, android.util.Base64.NO_WRAP)
        prefs
            .edit()
            .putString(CdnEchCacheConfigBytesB64Key, configB64)
            .putLong(CdnEchCacheFetchedAtKey, entry.fetchedAtUnixMs)
            .apply()
    }

    fun clear() {
        prefs
            .edit()
            .remove(CdnEchCacheConfigBytesB64Key)
            .remove(CdnEchCacheFetchedAtKey)
            .apply()
    }
}

internal class CdnEchCurrentPreferencesCodec(
    private val prefs: SharedPreferences,
) {
    fun load(): PersistedEchEntry? = CdnEchPreferencesCodec(prefs).load()

    fun isLegacyMigrationComplete(): Boolean = prefs.getBoolean(CdnEchCacheLegacyMigrationCompleteKey, false)

    fun save(entry: PersistedEchEntry) {
        editEntry(entry)
            .putBoolean(CdnEchCacheLegacyMigrationCompleteKey, true)
            .apply()
    }

    fun saveMigrated(entry: PersistedEchEntry): Boolean =
        editEntry(entry)
            .putBoolean(CdnEchCacheLegacyMigrationCompleteKey, true)
            .commit()

    fun clear() {
        prefs
            .edit()
            .remove(CdnEchCacheConfigBytesB64Key)
            .remove(CdnEchCacheFetchedAtKey)
            .putBoolean(CdnEchCacheLegacyMigrationCompleteKey, true)
            .apply()
    }

    private fun editEntry(entry: PersistedEchEntry): SharedPreferences.Editor {
        val configB64 = android.util.Base64.encodeToString(entry.configBytes, android.util.Base64.NO_WRAP)
        return prefs
            .edit()
            .putString(CdnEchCacheConfigBytesB64Key, configB64)
            .putLong(CdnEchCacheFetchedAtKey, entry.fetchedAtUnixMs)
    }
}

internal class CdnEchPreferencesMigrationCoordinator(
    private val currentCodec: CdnEchCurrentPreferencesCodec,
    private val loadLegacy: () -> PersistedEchEntry?,
) {
    fun load(): PersistedEchEntry? {
        val current = currentCodec.load()
        return current ?: when {
            currentCodec.isLegacyMigrationComplete() -> {
                null
            }

            else -> {
                runCatching { loadLegacy() }.getOrNull()?.let { legacy ->
                    legacy.takeIf { runCatching { currentCodec.saveMigrated(legacy) }.getOrDefault(false) }
                }
            }
        }
    }

    fun save(entry: PersistedEchEntry) {
        currentCodec.save(entry)
    }

    fun clear() {
        currentCodec.clear()
    }
}

// App-private cache for public CDN ECH config bytes.
// A lazy encrypted-preferences reader is retained for one transitional release.
@Singleton
class EncryptedSharedPreferencesCdnEchPersistedCache
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : CdnEchPersistedCache {
        private val currentPrefs: SharedPreferences by lazy {
            context.getSharedPreferences(CdnEchCacheCurrentPrefsName, Context.MODE_PRIVATE)
        }
        private val currentCodec: CdnEchCurrentPreferencesCodec by lazy {
            CdnEchCurrentPreferencesCodec(currentPrefs)
        }
        private val legacyPrefs: SharedPreferences by lazy {
            val masterKey =
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            EncryptedSharedPreferences.create(
                context,
                CdnEchCachePrefsName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        private val legacyCodec: CdnEchPreferencesCodec by lazy {
            CdnEchPreferencesCodec(legacyPrefs)
        }
        private val migrationCoordinator: CdnEchPreferencesMigrationCoordinator by lazy {
            CdnEchPreferencesMigrationCoordinator(currentCodec) { legacyCodec.load() }
        }

        override suspend fun load(): PersistedEchEntry? =
            withContext(Dispatchers.IO) {
                migrationCoordinator.load()
            }

        override suspend fun save(entry: PersistedEchEntry) {
            withContext(Dispatchers.IO) {
                migrationCoordinator.save(entry)
            }
        }

        override suspend fun clear() {
            withContext(Dispatchers.IO) {
                migrationCoordinator.clear()
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class CdnEchPersistedCacheModule {
    @Binds
    @Singleton
    abstract fun bindCdnEchPersistedCache(impl: EncryptedSharedPreferencesCdnEchPersistedCache): CdnEchPersistedCache
}
