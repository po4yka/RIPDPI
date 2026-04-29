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

private const val PREFS_NAME = "ripdpi_cdn_ech_cache"
private const val KEY_CONFIG_B64 = "config_bytes_b64"
private const val KEY_FETCHED_AT = "fetched_at_unix_ms"

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
        @ApplicationContext private val context: Context,
    ) : CdnEchPersistedCache {
        private val prefs: SharedPreferences by lazy {
            val masterKey =
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        override suspend fun load(): PersistedEchEntry? =
            withContext(Dispatchers.IO) {
                val configB64 = prefs.getString(KEY_CONFIG_B64, null) ?: return@withContext null
                val fetchedAt = prefs.getLong(KEY_FETCHED_AT, -1L)
                if (fetchedAt < 0L) return@withContext null
                val bytes =
                    runCatching {
                        android.util.Base64.decode(
                            configB64,
                            android.util.Base64.NO_WRAP,
                        )
                    }.getOrNull()
                bytes?.let { PersistedEchEntry(configBytes = it, fetchedAtUnixMs = fetchedAt) }
            }

        override suspend fun save(entry: PersistedEchEntry) {
            withContext(Dispatchers.IO) {
                val configB64 = android.util.Base64.encodeToString(entry.configBytes, android.util.Base64.NO_WRAP)
                prefs
                    .edit()
                    .putString(KEY_CONFIG_B64, configB64)
                    .putLong(KEY_FETCHED_AT, entry.fetchedAtUnixMs)
                    .apply()
            }
        }

        override suspend fun clear() {
            withContext(Dispatchers.IO) {
                prefs
                    .edit()
                    .remove(KEY_CONFIG_B64)
                    .remove(KEY_FETCHED_AT)
                    .apply()
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
