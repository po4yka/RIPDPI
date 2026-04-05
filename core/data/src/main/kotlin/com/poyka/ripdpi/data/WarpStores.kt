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
data class WarpProfile(
    val id: String = DefaultWarpProfileId,
    val accountKind: String = WarpAccountKindConsumerFree,
    val displayName: String = "",
    val zeroTrustOrg: String = "",
    val setupState: String = WarpSetupStateNotConfigured,
    val lastProvisionedAtEpochMillis: Long? = null,
)

@Serializable
data class WarpCredentials(
    val profileId: String = DefaultWarpProfileId,
    val deviceId: String,
    val accessToken: String,
    val accountId: String? = null,
    val accountKind: String = WarpAccountKindConsumerFree,
    val displayName: String = "",
    val zeroTrustOrg: String = "",
    val license: String? = null,
    val refreshToken: String? = null,
    val clientId: String? = null,
    val privateKey: String? = null,
    val publicKey: String? = null,
    val peerPublicKey: String? = null,
    val interfaceAddressV4: String? = null,
    val interfaceAddressV6: String? = null,
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
)

@Serializable
data class WarpEndpointCacheEntry(
    val profileId: String = DefaultWarpProfileId,
    val networkScopeKey: String,
    val host: String? = null,
    val ipv4: String? = null,
    val ipv6: String? = null,
    val port: Int,
    val source: String = "scanner",
    val rttMs: Long? = null,
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
)

const val GlobalWarpEndpointScopeKey: String = "__global__"

interface WarpProfileStore {
    suspend fun load(profileId: String): WarpProfile?

    suspend fun loadAll(): List<WarpProfile>

    suspend fun save(profile: WarpProfile)

    suspend fun remove(profileId: String)

    suspend fun activeProfileId(): String?

    suspend fun setActiveProfileId(profileId: String?)

    suspend fun clearAll()
}

interface WarpCredentialStore {
    suspend fun load(profileId: String): WarpCredentials?

    suspend fun loadAll(): List<WarpCredentials>

    suspend fun save(
        profileId: String,
        credentials: WarpCredentials,
    )

    suspend fun clear(profileId: String)

    suspend fun clearAll()
}

interface WarpEndpointStore {
    suspend fun load(
        profileId: String,
        networkScopeKey: String,
    ): WarpEndpointCacheEntry?

    suspend fun loadAll(profileId: String): List<WarpEndpointCacheEntry>

    suspend fun save(entry: WarpEndpointCacheEntry)

    suspend fun clear(
        profileId: String,
        networkScopeKey: String,
    )

    suspend fun clearProfile(profileId: String)

    suspend fun clearAll()
}

@Singleton
class SharedPreferencesWarpProfileStore
    @Inject
    constructor(
        @param:ApplicationContext context: Context,
    ) : WarpProfileStore {
        private val preferences = context.getSharedPreferences(ProfilePrefsName, Context.MODE_PRIVATE)
        private val json = Json { ignoreUnknownKeys = true }

        override suspend fun load(profileId: String): WarpProfile? =
            preferences.getString(prefKey(profileId), null)?.let {
                json.decodeFromString<WarpProfile>(it)
            }

        override suspend fun loadAll(): List<WarpProfile> =
            preferences.all.entries
                .asSequence()
                .filter { (key, value) -> key.startsWith(ProfileKeyPrefix) && value is String }
                .mapNotNull { (_, value) ->
                    runCatching {
                        json.decodeFromString<WarpProfile>(value as String)
                    }.getOrNull()
                }.sortedBy(WarpProfile::id)
                .toList()

        override suspend fun save(profile: WarpProfile) {
            val normalized =
                profile.copy(
                    id = profile.id.ifBlank { DefaultWarpProfileId },
                    accountKind = normalizeWarpAccountKind(profile.accountKind),
                    setupState = normalizeWarpSetupState(profile.setupState),
                )
            preferences
                .edit()
                .putString(
                    prefKey(normalized.id),
                    json.encodeToString(WarpProfile.serializer(), normalized),
                ).apply()
        }

        override suspend fun remove(profileId: String) {
            preferences.edit().remove(prefKey(profileId)).apply()
        }

        override suspend fun activeProfileId(): String? =
            preferences.getString(ActiveProfileKey, null)?.takeIf(String::isNotBlank)

        override suspend fun setActiveProfileId(profileId: String?) {
            preferences
                .edit()
                .also { editor ->
                    if (profileId.isNullOrBlank()) {
                        editor.remove(ActiveProfileKey)
                    } else {
                        editor.putString(ActiveProfileKey, profileId)
                    }
                }.apply()
        }

        override suspend fun clearAll() {
            preferences.edit().clear().apply()
        }

        private fun prefKey(profileId: String): String = "$ProfileKeyPrefix$profileId"

        private companion object {
            const val ProfilePrefsName = "warp_profiles"
            const val ProfileKeyPrefix = "profile:"
            const val ActiveProfileKey = "active_profile_id"
        }
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

        override suspend fun load(profileId: String): WarpCredentials? =
            blobStore
                .getString(prefKey(profileId))
                ?.let { payload ->
                    json.decodeFromString<WarpCredentials>(payload).copy(profileId = profileId)
                }
                ?: loadLegacy(profileId)

        override suspend fun loadAll(): List<WarpCredentials> =
            blobStore
                .keys()
                .asSequence()
                .filter { it.startsWith(CredentialsEntryPrefix) }
                .mapNotNull { key ->
                    blobStore.getString(key)?.let { payload ->
                        runCatching {
                            json.decodeFromString<WarpCredentials>(payload).copy(
                                profileId = key.removePrefix(CredentialsEntryPrefix),
                            )
                        }.getOrNull()
                    }
                }.sortedBy(WarpCredentials::profileId)
                .toList()

        override suspend fun save(
            profileId: String,
            credentials: WarpCredentials,
        ) {
            blobStore.putString(
                prefKey(profileId),
                json.encodeToString(
                    WarpCredentials.serializer(),
                    credentials.copy(
                        profileId = profileId,
                        accountKind = normalizeWarpAccountKind(credentials.accountKind),
                    ),
                ),
            )
        }

        override suspend fun clear(profileId: String) {
            blobStore.remove(prefKey(profileId))
            if (profileId == DefaultWarpProfileId) {
                blobStore.remove(LegacyCredentialsEntryKey)
            }
        }

        override suspend fun clearAll() {
            blobStore
                .keys()
                .filter { it == LegacyCredentialsEntryKey || it.startsWith(CredentialsEntryPrefix) }
                .forEach(blobStore::remove)
        }

        private fun loadLegacy(profileId: String): WarpCredentials? {
            if (profileId != DefaultWarpProfileId) {
                return null
            }
            return blobStore
                .getString(LegacyCredentialsEntryKey)
                ?.let { json.decodeFromString<WarpCredentials>(it).copy(profileId = profileId) }
        }

        private fun prefKey(profileId: String): String = "$CredentialsEntryPrefix$profileId"

        private companion object {
            const val CredentialsPrefsName = "warp_credentials_secure"
            const val LegacyCredentialsEntryKey = "warp_credentials"
            const val CredentialsEntryPrefix = "warp_credentials:"
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

        override suspend fun load(
            profileId: String,
            networkScopeKey: String,
        ): WarpEndpointCacheEntry? =
            preferences.getString(prefKey(profileId, normalizeScopeKey(networkScopeKey)), null)?.let {
                json.decodeFromString<WarpEndpointCacheEntry>(it)
            } ?: loadLegacy(profileId, normalizeScopeKey(networkScopeKey))

        override suspend fun loadAll(profileId: String): List<WarpEndpointCacheEntry> =
            preferences.all.entries
                .asSequence()
                .filter { (key, value) -> key.startsWith("endpoint:$profileId:") && value is String }
                .mapNotNull { (_, value) ->
                    runCatching {
                        json.decodeFromString<WarpEndpointCacheEntry>(value as String)
                    }.getOrNull()
                }.sortedBy(WarpEndpointCacheEntry::networkScopeKey)
                .toList()

        override suspend fun save(entry: WarpEndpointCacheEntry) {
            val profileId = entry.profileId.ifBlank { DefaultWarpProfileId }
            val networkScopeKey = normalizeScopeKey(entry.networkScopeKey)
            preferences
                .edit()
                .putString(
                    prefKey(profileId, networkScopeKey),
                    json.encodeToString(
                        WarpEndpointCacheEntry.serializer(),
                        entry.copy(
                            profileId = profileId,
                            networkScopeKey = networkScopeKey,
                        ),
                    ),
                ).apply()
        }

        override suspend fun clear(
            profileId: String,
            networkScopeKey: String,
        ) {
            preferences.edit().remove(prefKey(profileId, normalizeScopeKey(networkScopeKey))).apply()
        }

        override suspend fun clearProfile(profileId: String) {
            preferences
                .all
                .keys
                .filter { it.startsWith("endpoint:$profileId:") }
                .forEach { key -> preferences.edit().remove(key).apply() }
        }

        override suspend fun clearAll() {
            preferences.edit().clear().apply()
        }

        private fun loadLegacy(
            profileId: String,
            networkScopeKey: String,
        ): WarpEndpointCacheEntry? {
            if (profileId != DefaultWarpProfileId) {
                return null
            }
            return preferences.getString(legacyPrefKey(networkScopeKey), null)?.let {
                json.decodeFromString<WarpEndpointCacheEntry>(it).copy(profileId = profileId)
            }
        }

        private fun prefKey(
            profileId: String,
            networkScopeKey: String,
        ): String = "endpoint:$profileId:$networkScopeKey"

        private fun normalizeScopeKey(networkScopeKey: String): String =
            networkScopeKey.takeIf(String::isNotBlank) ?: GlobalWarpEndpointScopeKey

        private fun legacyPrefKey(networkScopeKey: String): String = "endpoint:$networkScopeKey"

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

    fun keys(): Set<String> = preferences.all.keys

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
    abstract fun bindWarpProfileStore(store: SharedPreferencesWarpProfileStore): WarpProfileStore

    @Binds
    @Singleton
    abstract fun bindWarpCredentialStore(store: KeystoreWarpCredentialStore): WarpCredentialStore

    @Binds
    @Singleton
    abstract fun bindWarpEndpointStore(store: SharedPreferencesWarpEndpointStore): WarpEndpointStore
}
