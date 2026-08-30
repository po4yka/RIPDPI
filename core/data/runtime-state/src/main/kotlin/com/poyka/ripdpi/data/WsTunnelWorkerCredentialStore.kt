package com.poyka.ripdpi.data

import android.content.Context
import com.poyka.ripdpi.proto.AppSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

interface WsTunnelWorkerCredentialStore {
    suspend fun load(credentialRef: String): String?

    suspend fun save(
        credentialRef: String,
        bearer: String,
    )

    suspend fun clear(credentialRef: String)

    suspend fun clearAll()
}

suspend fun WsTunnelWorkerCredentialStore.resolveTransport(settings: AppSettings): CloudflareWorkerTransportConfig? {
    val workerUrl = settings.wsTunnelWorkerUrl.trim()
    val credentialRef = settings.wsTunnelWorkerCredentialRef.trim()
    if (workerUrl.isEmpty() && credentialRef.isEmpty()) return null
    require(workerUrl.isNotEmpty() && credentialRef.isNotEmpty()) {
        "Cloudflare Worker URL and credential reference must be configured together"
    }
    val bearer =
        requireNotNull(load(credentialRef)) {
            "Cloudflare Worker credential is unavailable for the configured reference"
        }
    return CloudflareWorkerTransportConfig(workerUrl, credentialRef, SecretString(bearer))
}

@Singleton
class KeystoreWsTunnelWorkerCredentialStore
    internal constructor(
        private val backend: WsTunnelWorkerCredentialBackend,
    ) : WsTunnelWorkerCredentialStore {
        @Inject
        constructor(
            @ApplicationContext context: Context,
        ) : this(
            KeystoreWsTunnelWorkerCredentialBackend(
                KeystoreEncryptedPreferences(
                    preferences = context.getSharedPreferences(CredentialsPrefsName, Context.MODE_PRIVATE),
                    keyAlias = CredentialsKeyAlias,
                ),
            ),
        )

        override suspend fun load(credentialRef: String): String? =
            normalizeRef(credentialRef)?.let { ref ->
                withContext(Dispatchers.IO) { backend.getStringStrict(prefKey(ref)) }
            }

        override suspend fun save(
            credentialRef: String,
            bearer: String,
        ) {
            val ref =
                requireNotNull(normalizeRef(credentialRef)) {
                    "Cloudflare Worker credential reference must not be blank"
                }
            require(bearer.isNotBlank()) { "Cloudflare Worker bearer must not be blank" }
            require(bearer.none(Char::isISOControl)) {
                "Cloudflare Worker bearer must not contain control characters"
            }
            withContext(Dispatchers.IO) { backend.putString(prefKey(ref), bearer) }
        }

        override suspend fun clear(credentialRef: String) {
            val ref = normalizeRef(credentialRef) ?: return
            withContext(Dispatchers.IO) { backend.remove(prefKey(ref)) }
        }

        override suspend fun clearAll() {
            withContext(Dispatchers.IO) { backend.clear() }
        }

        private fun normalizeRef(credentialRef: String): String? = credentialRef.trim().takeIf { it.isNotEmpty() }

        private fun prefKey(credentialRef: String): String = "$CredentialsEntryPrefix$credentialRef"

        companion object {
            internal const val CredentialsPrefsName = "ws_tunnel_worker_credentials"
            const val CredentialsKeyAlias = "ripdpi_ws_tunnel_worker_credentials"
            const val CredentialsEntryPrefix = "worker:"
        }
    }

internal interface WsTunnelWorkerCredentialBackend {
    fun getStringStrict(key: String): String?

    fun putString(
        key: String,
        value: String,
    )

    fun remove(key: String)

    fun clear()
}

private class KeystoreWsTunnelWorkerCredentialBackend(
    private val encryptedPreferences: KeystoreEncryptedPreferences,
) : WsTunnelWorkerCredentialBackend {
    override fun getStringStrict(key: String): String? = encryptedPreferences.getStringStrict(key)

    override fun putString(
        key: String,
        value: String,
    ) = encryptedPreferences.putString(key, value)

    override fun remove(key: String) = encryptedPreferences.remove(key)

    override fun clear() = encryptedPreferences.clear()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WsTunnelWorkerCredentialStoreModule {
    @Binds
    @Singleton
    abstract fun bindWsTunnelWorkerCredentialStore(
        store: KeystoreWsTunnelWorkerCredentialStore,
    ): WsTunnelWorkerCredentialStore
}
