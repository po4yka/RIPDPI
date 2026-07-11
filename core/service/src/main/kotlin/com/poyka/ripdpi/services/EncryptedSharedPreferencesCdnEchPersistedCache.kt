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

internal const val CDN_ECH_CACHE_PREFS_NAME = "ripdpi_cdn_ech_cache"
internal const val CDN_ECH_CACHE_CURRENT_PREFS_NAME = "ripdpi_cdn_ech_cache_v2"
internal const val CDN_ECH_CACHE_CONFIG_BYTES_B64_KEY = "config_bytes_b64"
internal const val CDN_ECH_CACHE_FETCHED_AT_KEY = "fetched_at_unix_ms"
internal const val CDN_ECH_CACHE_LEGACY_MIGRATION_COMPLETE_KEY = "legacy_migration_complete_v1"

internal class CdnEchPreferencesCodec(
    private val prefs: SharedPreferences,
) {
    fun load(): PersistedEchEntry? {
        val configB64 = prefs.getString(CDN_ECH_CACHE_CONFIG_BYTES_B64_KEY, null) ?: return null
        val fetchedAt = prefs.getLong(CDN_ECH_CACHE_FETCHED_AT_KEY, -1L)
        if (fetchedAt < 0L) return null
        val bytes =
            runCatching {
                android.util.Base64.decode(
                    configB64,
                    android.util.Base64.NO_WRAP,
                )
            }.getOrNull()
        return bytes?.let { PersistedEchEntry(configBytes = it, fetchedAtUnixMs = fetchedAt) }
    }

    fun save(entry: PersistedEchEntry) {
        val configB64 = android.util.Base64.encodeToString(entry.configBytes, android.util.Base64.NO_WRAP)
        prefs
            .edit()
            .putString(CDN_ECH_CACHE_CONFIG_BYTES_B64_KEY, configB64)
            .putLong(CDN_ECH_CACHE_FETCHED_AT_KEY, entry.fetchedAtUnixMs)
            .apply()
    }

    fun clear() {
        prefs
            .edit()
            .remove(CDN_ECH_CACHE_CONFIG_BYTES_B64_KEY)
            .remove(CDN_ECH_CACHE_FETCHED_AT_KEY)
            .apply()
    }
}

internal class CdnEchCurrentPreferencesCodec(
    private val prefs: SharedPreferences,
) {
    fun load(): PersistedEchEntry? = CdnEchPreferencesCodec(prefs).load()

    fun isLegacyMigrationComplete(): Boolean = prefs.getBoolean(CDN_ECH_CACHE_LEGACY_MIGRATION_COMPLETE_KEY, false)

    fun save(entry: PersistedEchEntry) {
        editEntry(entry)
            .putBoolean(CDN_ECH_CACHE_LEGACY_MIGRATION_COMPLETE_KEY, true)
            .apply()
    }

    fun saveMigrated(entry: PersistedEchEntry): Boolean =
        editEntry(entry)
            .putBoolean(CDN_ECH_CACHE_LEGACY_MIGRATION_COMPLETE_KEY, true)
            .commit()

    fun clear() {
        prefs
            .edit()
            .remove(CDN_ECH_CACHE_CONFIG_BYTES_B64_KEY)
            .remove(CDN_ECH_CACHE_FETCHED_AT_KEY)
            .putBoolean(CDN_ECH_CACHE_LEGACY_MIGRATION_COMPLETE_KEY, true)
            .apply()
    }

    private fun editEntry(entry: PersistedEchEntry): SharedPreferences.Editor {
        val configB64 = android.util.Base64.encodeToString(entry.configBytes, android.util.Base64.NO_WRAP)
        return prefs
            .edit()
            .putString(CDN_ECH_CACHE_CONFIG_BYTES_B64_KEY, configB64)
            .putLong(CDN_ECH_CACHE_FETCHED_AT_KEY, entry.fetchedAtUnixMs)
    }
}

internal class CdnEchPreferencesMigrationCoordinator(
    private val currentCodec: CdnEchCurrentPreferencesCodec,
    private val loadLegacy: () -> PersistedEchEntry?,
) {
    fun load(): PersistedEchEntry? {
        val current = currentCodec.load()
        if (current != null || currentCodec.isLegacyMigrationComplete()) return current

        val legacy = runCatching { loadLegacy() }.getOrNull() ?: return null
        val migrated = runCatching { currentCodec.saveMigrated(legacy) }.getOrDefault(false)
        return legacy.takeIf { migrated }
    }

    fun save(entry: PersistedEchEntry) {
        currentCodec.save(entry)
    }

    fun clear() {
        currentCodec.clear()
    }
}

// App-private cache for public CDN ECH config bytes, with a lazy encrypted-preferences reader retained for one transitional release.
@Singleton
class EncryptedSharedPreferencesCdnEchPersistedCache
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : CdnEchPersistedCache {
        private val currentPrefs: SharedPreferences by lazy {
            context.getSharedPreferences(CDN_ECH_CACHE_CURRENT_PREFS_NAME, Context.MODE_PRIVATE)
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
                CDN_ECH_CACHE_PREFS_NAME,
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
