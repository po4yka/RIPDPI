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

internal const val SHARED_PRIORS_REFRESH_PREFS_NAME = "ripdpi_shared_priors_refresh"
internal const val SHARED_PRIORS_REFRESH_LAST_REFRESH_KEY = "last_refresh_unix_ms"
internal const val SHARED_PRIORS_REFRESH_LAST_MODIFIED_KEY = "last_modified_header"

internal class SharedPriorsRefreshPreferencesCodec(
    private val prefs: SharedPreferences,
) {
    fun load(): SharedPriorsRefreshState? {
        val lastRefresh = prefs.getLong(SHARED_PRIORS_REFRESH_LAST_REFRESH_KEY, -1L)
        if (lastRefresh < 0L) return null
        val lastModified = prefs.getString(SHARED_PRIORS_REFRESH_LAST_MODIFIED_KEY, null)
        return SharedPriorsRefreshState(lastRefreshUnixMs = lastRefresh, lastModifiedHeader = lastModified)
    }

    fun save(state: SharedPriorsRefreshState) {
        val editor = prefs.edit().putLong(SHARED_PRIORS_REFRESH_LAST_REFRESH_KEY, state.lastRefreshUnixMs)
        if (state.lastModifiedHeader != null) {
            editor.putString(SHARED_PRIORS_REFRESH_LAST_MODIFIED_KEY, state.lastModifiedHeader)
        } else {
            editor.remove(SHARED_PRIORS_REFRESH_LAST_MODIFIED_KEY)
        }
        editor.apply()
    }
}

@Singleton
class EncryptedSharedPreferencesSharedPriorsRefreshCache
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : SharedPriorsRefreshCache {
        private val prefs: SharedPreferences by lazy {
            val masterKey =
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            EncryptedSharedPreferences.create(
                context,
                SHARED_PRIORS_REFRESH_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        private val codec: SharedPriorsRefreshPreferencesCodec by lazy {
            SharedPriorsRefreshPreferencesCodec(prefs)
        }

        override suspend fun load(): SharedPriorsRefreshState? =
            withContext(Dispatchers.IO) {
                codec.load()
            }

        override suspend fun save(state: SharedPriorsRefreshState) {
            withContext(Dispatchers.IO) {
                codec.save(state)
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
