package com.poyka.ripdpi.data

import android.content.Context
import android.content.SharedPreferences
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class RelayProfileRecord(
    val id: String = DefaultRelayProfileId,
    val kind: String = RelayKindOff,
    val server: String = "",
    val serverPort: Int = 443,
    val serverName: String = "",
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val chainEntryServer: String = "",
    val chainEntryPort: Int = 443,
    val chainEntryServerName: String = "",
    val chainEntryPublicKey: String = "",
    val chainEntryShortId: String = "",
    val chainExitServer: String = "",
    val chainExitPort: Int = 443,
    val chainExitServerName: String = "",
    val chainExitPublicKey: String = "",
    val chainExitShortId: String = "",
    val masqueUrl: String = "",
    val masqueUseHttp2Fallback: Boolean = true,
    val masqueCloudflareMode: Boolean = false,
    val localSocksHost: String = DefaultRelayLocalSocksHost,
    val localSocksPort: Int = DefaultRelayLocalSocksPort,
    val udpEnabled: Boolean = false,
    val tcpFallbackEnabled: Boolean = true,
)

@Serializable
data class RelayCredentialRecord(
    val profileId: String,
    val vlessUuid: String? = null,
    val chainEntryUuid: String? = null,
    val chainExitUuid: String? = null,
    val hysteriaPassword: String? = null,
    val hysteriaSalamanderKey: String? = null,
    val masqueAuthMode: String? = null,
    val masqueAuthToken: String? = null,
    val masqueCloudflareClientId: String? = null,
    val masqueCloudflareKeyId: String? = null,
    val masqueCloudflarePrivateKeyPem: String? = null,
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
)

interface RelayProfileStore {
    suspend fun load(profileId: String): RelayProfileRecord?

    suspend fun save(profile: RelayProfileRecord)

    suspend fun clear(profileId: String)
}

interface RelayCredentialStore {
    suspend fun load(profileId: String): RelayCredentialRecord?

    suspend fun save(credentials: RelayCredentialRecord)

    suspend fun clear(profileId: String)
}

@Singleton
class SharedPreferencesRelayProfileStore
    @Inject
    constructor(
        @param:ApplicationContext context: Context,
    ) : RelayProfileStore {
        private val preferences = context.getSharedPreferences(ProfilePrefsName, Context.MODE_PRIVATE)
        private val json = Json { ignoreUnknownKeys = true }

        override suspend fun load(profileId: String): RelayProfileRecord? =
            preferences.getString(prefKey(profileId), null)?.let {
                json.decodeFromString(RelayProfileRecord.serializer(), it)
            }

        override suspend fun save(profile: RelayProfileRecord) {
            preferences
                .edit()
                .putString(
                    prefKey(profile.id),
                    json.encodeToString(RelayProfileRecord.serializer(), profile),
                ).apply()
        }

        override suspend fun clear(profileId: String) {
            preferences.edit().remove(prefKey(profileId)).apply()
        }

        private fun prefKey(profileId: String): String = "relay-profile:$profileId"

        private companion object {
            const val ProfilePrefsName = "relay_profile_cache"
        }
    }

@Singleton
class KeystoreRelayCredentialStore
    @Inject
    constructor(
        @param:ApplicationContext context: Context,
    ) : RelayCredentialStore {
        private val json = Json { ignoreUnknownKeys = true }
        private val blobStore =
            KeystoreEncryptedPreferences(
                preferences = context.getSharedPreferences(CredentialsPrefsName, Context.MODE_PRIVATE),
                keyAlias = CredentialsKeyAlias,
            )

        override suspend fun load(profileId: String): RelayCredentialRecord? =
            blobStore
                .getString(prefKey(profileId))
                ?.let { json.decodeFromString(RelayCredentialRecord.serializer(), it) }

        override suspend fun save(credentials: RelayCredentialRecord) {
            blobStore.putString(
                prefKey(credentials.profileId),
                json.encodeToString(RelayCredentialRecord.serializer(), credentials),
            )
        }

        override suspend fun clear(profileId: String) {
            blobStore.remove(prefKey(profileId))
        }

        private fun prefKey(profileId: String): String = "relay-credentials:$profileId"

        private companion object {
            const val CredentialsPrefsName = "relay_credentials_secure"
            const val CredentialsKeyAlias = "ripdpi_relay_credentials"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class RelayStoreModule {
    @Binds
    @Singleton
    abstract fun bindRelayProfileStore(store: SharedPreferencesRelayProfileStore): RelayProfileStore

    @Binds
    @Singleton
    abstract fun bindRelayCredentialStore(store: KeystoreRelayCredentialStore): RelayCredentialStore
}
