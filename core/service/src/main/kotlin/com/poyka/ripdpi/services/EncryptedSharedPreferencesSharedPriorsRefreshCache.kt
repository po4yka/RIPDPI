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

private const val prefsName = "ripdpi_shared_priors_refresh"
private const val keyLastRefresh = "last_refresh_unix_ms"
private const val keyLastModified = "last_modified_header"

@Singleton
class EncryptedSharedPreferencesSharedPriorsRefreshCache
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SharedPriorsRefreshCache {
        private val prefs: SharedPreferences by lazy {
            val masterKey =
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            EncryptedSharedPreferences.create(
                context,
                prefsName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        override suspend fun load(): SharedPriorsRefreshState? =
            withContext(Dispatchers.IO) {
                val lastRefresh = prefs.getLong(keyLastRefresh, -1L)
                if (lastRefresh < 0L) return@withContext null
                val lastModified = prefs.getString(keyLastModified, null)
                SharedPriorsRefreshState(lastRefreshUnixMs = lastRefresh, lastModifiedHeader = lastModified)
            }

        override suspend fun save(state: SharedPriorsRefreshState) {
            withContext(Dispatchers.IO) {
                val editor = prefs.edit().putLong(keyLastRefresh, state.lastRefreshUnixMs)
                if (state.lastModifiedHeader != null) {
                    editor.putString(keyLastModified, state.lastModifiedHeader)
                } else {
                    editor.remove(keyLastModified)
                }
                editor.apply()
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
