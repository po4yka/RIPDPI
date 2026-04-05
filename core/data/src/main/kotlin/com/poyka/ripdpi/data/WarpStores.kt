package com.poyka.ripdpi.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class WarpCredentials(
    val deviceId: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val clientId: String? = null,
    val privateKey: String? = null,
    val publicKey: String? = null,
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
)

@Serializable
data class WarpEndpointCacheEntry(
    val networkScopeKey: String,
    val host: String? = null,
    val ipv4: String? = null,
    val ipv6: String? = null,
    val port: Int,
    val source: String = "scanner",
    val rttMs: Long? = null,
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
)

interface WarpCredentialStore {
    suspend fun load(): WarpCredentials?

    suspend fun save(credentials: WarpCredentials)

    suspend fun clear()
}

interface WarpEndpointStore {
    suspend fun load(networkScopeKey: String): WarpEndpointCacheEntry?

    suspend fun save(entry: WarpEndpointCacheEntry)

    suspend fun clear(networkScopeKey: String)

    suspend fun clearAll()
}

@Singleton
class KeystoreWarpCredentialStore
    @Inject
    constructor(
        @param:ApplicationContext context: Context,
    ) : WarpCredentialStore {
        private val json = Json { ignoreUnknownKeys = true }
        private val blobStore =
            KeystoreEncryptedPreferences(
                preferences = context.getSharedPreferences(CredentialsPrefsName, Context.MODE_PRIVATE),
                keyAlias = CredentialsKeyAlias,
            )

        override suspend fun load(): WarpCredentials? =
            blobStore
                .getString(CredentialsEntryKey)
                ?.let { json.decodeFromString<WarpCredentials>(it) }

        override suspend fun save(credentials: WarpCredentials) {
            blobStore.putString(
                CredentialsEntryKey,
                json.encodeToString(WarpCredentials.serializer(), credentials),
            )
        }

        override suspend fun clear() {
            blobStore.remove(CredentialsEntryKey)
        }

        private companion object {
            const val CredentialsPrefsName = "warp_credentials_secure"
            const val CredentialsEntryKey = "warp_credentials"
            const val CredentialsKeyAlias = "ripdpi_warp_credentials"
        }
    }

@Singleton
class SharedPreferencesWarpEndpointStore
    @Inject
    constructor(
        @param:ApplicationContext context: Context,
    ) : WarpEndpointStore {
        private val preferences = context.getSharedPreferences(EndpointPrefsName, Context.MODE_PRIVATE)
        private val json = Json { ignoreUnknownKeys = true }

        override suspend fun load(networkScopeKey: String): WarpEndpointCacheEntry? =
            preferences.getString(prefKey(networkScopeKey), null)?.let {
                json.decodeFromString<WarpEndpointCacheEntry>(it)
            }

        override suspend fun save(entry: WarpEndpointCacheEntry) {
            preferences
                .edit()
                .putString(
                    prefKey(entry.networkScopeKey),
                    json.encodeToString(WarpEndpointCacheEntry.serializer(), entry),
                ).apply()
        }

        override suspend fun clear(networkScopeKey: String) {
            preferences.edit().remove(prefKey(networkScopeKey)).apply()
        }

        override suspend fun clearAll() {
            preferences.edit().clear().apply()
        }

        private fun prefKey(networkScopeKey: String): String = "endpoint:$networkScopeKey"

        private companion object {
            const val EndpointPrefsName = "warp_endpoint_cache"
        }
    }

internal class KeystoreEncryptedPreferences(
    private val preferences: SharedPreferences,
    private val keyAlias: String,
) {
    fun getString(key: String): String? {
        val payload = preferences.getString(key, null) ?: return null
        return runCatching { decrypt(payload) }.getOrNull()
    }

    fun putString(
        key: String,
        value: String,
    ) {
        preferences.edit().putString(key, encrypt(value)).apply()
    }

    fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(AesTransformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(Int.SIZE_BYTES + iv.size + ciphertext.size)
        payload.putInt(iv.size)
        payload.put(iv)
        payload.put(ciphertext)
        return Base64.encodeToString(payload.array(), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val buffer = ByteBuffer.wrap(bytes)
        val ivLength = buffer.int
        val iv = ByteArray(ivLength)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)
        val cipher = Cipher.getInstance(AesTransformation)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GcmTagLengthBits, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keystore = KeyStore.getInstance(KeystoreProvider).apply { load(null) }
        val existing = keystore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) {
            return existing
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KeystoreProvider)
        generator.init(
            KeyGenParameterSpec
                .Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KeystoreProvider = "AndroidKeyStore"
        const val AesTransformation = "AES/GCM/NoPadding"
        const val GcmTagLengthBits = 128
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WarpStoreModule {
    @Binds
    @Singleton
    abstract fun bindWarpCredentialStore(store: KeystoreWarpCredentialStore): WarpCredentialStore

    @Binds
    @Singleton
    abstract fun bindWarpEndpointStore(store: SharedPreferencesWarpEndpointStore): WarpEndpointStore
}
