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
internal const val CDN_ECH_CACHE_CONFIG_BYTES_B64_KEY = "config_bytes_b64"
internal const val CDN_ECH_CACHE_FETCHED_AT_KEY = "fetched_at_unix_ms"

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

// EncryptedSharedPreferences-backed cache for the most-recent ECH config
// bytes. The bytes themselves are public CDN data, so the encryption is
// primarily about tampering protection, not
// confidentiality — a malicious app on the same device cannot rewrite the
// cache to point at a stale or attacker-supplied config.
//
// Reads and writes hop to Dispatchers.IO because EncryptedSharedPreferences
// performs disk + Keystore work under the hood.
@Singleton
class EncryptedSharedPreferencesCdnEchPersistedCache
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : CdnEchPersistedCache {
        private val prefs: SharedPreferences by lazy {
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
        private val codec: CdnEchPreferencesCodec by lazy {
            CdnEchPreferencesCodec(prefs)
        }

        override suspend fun load(): PersistedEchEntry? =
            withContext(Dispatchers.IO) {
                codec.load()
            }

        override suspend fun save(entry: PersistedEchEntry) {
            withContext(Dispatchers.IO) {
                codec.save(entry)
            }
        }

        override suspend fun clear() {
            withContext(Dispatchers.IO) {
                codec.clear()
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
