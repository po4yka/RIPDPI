package com.poyka.ripdpi.data.awg

import android.content.Context
import com.poyka.ripdpi.data.KeystoreEncryptedPreferences
import com.poyka.ripdpi.serialization.RipDpiContractJson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The secret material of a standalone AmneziaWG profile, held apart from the
 * durable Room blob so the WireGuard private key and preshared key are never
 * persisted in plaintext.
 *
 * Mirrors the WARP credential split (`WarpCredentials`): the Room row keeps the
 * non-secret user config (endpoint host/port, addresses, obfuscation knobs) and
 * these two fields live encrypted at rest, keyed by the opaque profile id.
 */
@Serializable
data class AwgSecrets(
    val privateKey: String = "",
    val presharedKey: String = "",
)

/**
 * Encrypted-at-rest store for the standalone-AmneziaWG secret fields.
 *
 * The canonical reference is `WarpCredentialStore`: the secrets are AES-256-GCM
 * encrypted via the AndroidKeyStore-backed `KeystoreEncryptedPreferences`
 * primitive and addressed by the opaque `"awg-<UUID>"` profile id. The Room blob
 * (`AwgProfileEntity.requestJson`) carries NO key material; the repository blanks
 * `privateKey`/`presharedKey` there and rehydrates them from this store.
 */
interface AwgCredentialStore {
    /** Loads the secrets for [profileId], or `null` when none are stored. */
    suspend fun load(profileId: String): AwgSecrets?

    /** Encrypts and persists [secrets] under [profileId]. */
    suspend fun save(
        profileId: String,
        secrets: AwgSecrets,
    )

    /** Removes the secrets for [profileId]; a no-op when none exist. */
    suspend fun clear(profileId: String)
}

/**
 * AndroidKeyStore-backed [AwgCredentialStore]. Reuses the exact encryption usage
 * of `KeystoreWarpCredentialStore` -- `KeystoreEncryptedPreferences` performs the
 * AES-256-GCM seal/open; no crypto is hand-rolled here.
 */
@Singleton
class KeystoreAwgCredentialStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : AwgCredentialStore {
        private val json = RipDpiContractJson
        private val blobStore =
            KeystoreEncryptedPreferences(
                preferences = context.getSharedPreferences(CredentialsPrefsName, Context.MODE_PRIVATE),
                keyAlias = CredentialsKeyAlias,
            )

        override suspend fun load(profileId: String): AwgSecrets? =
            blobStore.getString(prefKey(profileId))?.let { payload ->
                runCatching { json.decodeFromString<AwgSecrets>(payload) }.getOrNull()
            }

        override suspend fun save(
            profileId: String,
            secrets: AwgSecrets,
        ) {
            blobStore.putString(
                prefKey(profileId),
                json.encodeToString(AwgSecrets.serializer(), secrets),
            )
        }

        override suspend fun clear(profileId: String) {
            blobStore.remove(prefKey(profileId))
        }

        private fun prefKey(profileId: String): String = "$CredentialsEntryPrefix$profileId"

        private companion object {
            const val CredentialsPrefsName = "awg_credentials_secure"
            const val CredentialsEntryPrefix = "awg_credentials:"
            const val CredentialsKeyAlias = "ripdpi_awg_credentials"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class AwgStoreModule {
    @Binds
    @Singleton
    abstract fun bindAwgCredentialStore(store: KeystoreAwgCredentialStore): AwgCredentialStore
}
