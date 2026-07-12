@file:Suppress("DEPRECATION")

package com.poyka.ripdpi.services

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.poyka.ripdpi.data.SharedPriorsRefreshCache
import com.poyka.ripdpi.data.SharedPriorsRefreshState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

internal const val SharedPriorsRefreshPrefsName = "ripdpi_shared_priors_refresh"
internal const val SharedPriorsRefreshCurrentPrefsName = "ripdpi_shared_priors_refresh_v2"
internal const val SharedPriorsRefreshLastRefreshKey = "last_refresh_unix_ms"
internal const val SharedPriorsRefreshLastModifiedKey = "last_modified_header"
internal const val SharedPriorsRefreshLegacyMigrationCompleteKey = "legacy_migration_complete_v1"

@Suppress("TopLevelPropertyNaming")
internal const val SHARED_PRIORS_REFRESH_PREFS_NAME = SharedPriorsRefreshPrefsName

@Suppress("TopLevelPropertyNaming")
internal const val SHARED_PRIORS_REFRESH_CURRENT_PREFS_NAME = SharedPriorsRefreshCurrentPrefsName

@Suppress("TopLevelPropertyNaming")
internal const val SHARED_PRIORS_REFRESH_LAST_REFRESH_KEY = SharedPriorsRefreshLastRefreshKey

@Suppress("TopLevelPropertyNaming")
internal const val SHARED_PRIORS_REFRESH_LAST_MODIFIED_KEY = SharedPriorsRefreshLastModifiedKey

@Suppress("TopLevelPropertyNaming")
internal const val SHARED_PRIORS_REFRESH_LEGACY_MIGRATION_COMPLETE_KEY = SharedPriorsRefreshLegacyMigrationCompleteKey

internal class SharedPriorsRefreshPreferencesCodec(
    private val prefs: SharedPreferences,
) {
    fun load(): SharedPriorsRefreshState? {
        val lastRefresh = prefs.getLong(SharedPriorsRefreshLastRefreshKey, -1L)
        return lastRefresh
            .takeIf { it >= 0L }
            ?.let {
                SharedPriorsRefreshState(
                    lastRefreshUnixMs = it,
                    lastModifiedHeader = prefs.getString(SharedPriorsRefreshLastModifiedKey, null),
                )
            }
    }

    fun save(state: SharedPriorsRefreshState) {
        val editor = prefs.edit().putLong(SharedPriorsRefreshLastRefreshKey, state.lastRefreshUnixMs)
        if (state.lastModifiedHeader != null) {
            editor.putString(SharedPriorsRefreshLastModifiedKey, state.lastModifiedHeader)
        } else {
            editor.remove(SharedPriorsRefreshLastModifiedKey)
        }
        editor.apply()
    }
}

internal class SharedPriorsRefreshCurrentPreferencesCodec(
    private val prefs: SharedPreferences,
) {
    fun load(): SharedPriorsRefreshState? = SharedPriorsRefreshPreferencesCodec(prefs).load()

    fun isLegacyMigrationComplete(): Boolean = prefs.getBoolean(SharedPriorsRefreshLegacyMigrationCompleteKey, false)

    fun save(state: SharedPriorsRefreshState) {
        editState(state)
            .putBoolean(SharedPriorsRefreshLegacyMigrationCompleteKey, true)
            .apply()
    }

    fun saveMigrated(state: SharedPriorsRefreshState): Boolean =
        editState(state)
            .putBoolean(SharedPriorsRefreshLegacyMigrationCompleteKey, true)
            .commit()

    private fun editState(state: SharedPriorsRefreshState): SharedPreferences.Editor {
        val editor = prefs.edit().putLong(SharedPriorsRefreshLastRefreshKey, state.lastRefreshUnixMs)
        return if (state.lastModifiedHeader != null) {
            editor.putString(SharedPriorsRefreshLastModifiedKey, state.lastModifiedHeader)
        } else {
            editor.remove(SharedPriorsRefreshLastModifiedKey)
        }
    }
}

internal class SharedPriorsRefreshPreferencesMigrationCoordinator(
    private val currentCodec: SharedPriorsRefreshCurrentPreferencesCodec,
    private val loadLegacy: () -> SharedPriorsRefreshState?,
) {
    fun load(): SharedPriorsRefreshState? {
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

    fun save(state: SharedPriorsRefreshState) {
        currentCodec.save(state)
    }
}

@Singleton
class EncryptedSharedPreferencesSharedPriorsRefreshCache
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : SharedPriorsRefreshCache {
        private val currentPrefs: SharedPreferences by lazy {
            context.getSharedPreferences(SharedPriorsRefreshCurrentPrefsName, Context.MODE_PRIVATE)
        }
        private val currentCodec: SharedPriorsRefreshCurrentPreferencesCodec by lazy {
            SharedPriorsRefreshCurrentPreferencesCodec(currentPrefs)
        }
        private val legacyPrefs: SharedPreferences by lazy {
            val masterKey =
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            EncryptedSharedPreferences.create(
                context,
                SharedPriorsRefreshPrefsName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        private val legacyCodec: SharedPriorsRefreshPreferencesCodec by lazy {
            SharedPriorsRefreshPreferencesCodec(legacyPrefs)
        }
        private val migrationCoordinator: SharedPriorsRefreshPreferencesMigrationCoordinator by lazy {
            SharedPriorsRefreshPreferencesMigrationCoordinator(currentCodec) { legacyCodec.load() }
        }

        override suspend fun load(): SharedPriorsRefreshState? =
            withContext(Dispatchers.IO) {
                migrationCoordinator.load()
            }

        override suspend fun save(state: SharedPriorsRefreshState) {
            withContext(Dispatchers.IO) {
                migrationCoordinator.save(state)
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class SharedPriorsRefreshCacheModule {
    @Binds
    @Singleton
    abstract fun bindSharedPriorsRefreshCache(
        impl: EncryptedSharedPreferencesSharedPriorsRefreshCache,
    ): SharedPriorsRefreshCache
}
