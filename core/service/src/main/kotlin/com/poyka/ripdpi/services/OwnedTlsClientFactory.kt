@file:Suppress("MagicNumber")

package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.StrategyPackStateStore
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import com.poyka.ripdpi.data.TlsFingerprintProfileEdgeStable
import com.poyka.ripdpi.data.TlsFingerprintProfileFirefoxStable
import com.poyka.ripdpi.data.TlsFingerprintProfileSafariStable
import com.poyka.ripdpi.data.diagnostics.DiagnosticsHttpClientFactory
import com.poyka.ripdpi.data.normalizeTlsFingerprintProfile
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.random.Random

interface OwnedTlsFingerprintProfileProvider {
    fun currentProfile(): String
}

data class OwnedTlsFingerprintSelection(
    val profileId: String = TlsFingerprintProfileChromeStable,
    val profileSetId: String = com.poyka.ripdpi.data.DefaultTlsProfileSetId,
    val catalogVersion: String = com.poyka.ripdpi.data.DefaultTlsProfileCatalogVersion,
)

interface OwnedTlsClientFactory {
    fun currentProfile(): String

    fun selectionForAuthority(authority: String?): OwnedTlsFingerprintSelection

    fun create(
        forcedTlsVersions: List<TlsVersion>? = null,
        configure: OkHttpClient.Builder.() -> Unit = {},
    ): OkHttpClient

    fun createForAuthority(
        authority: String?,
        forcedTlsVersions: List<TlsVersion>? = null,
        configure: OkHttpClient.Builder.() -> Unit = {},
    ): OkHttpClient
}

@Singleton
class SettingsBackedOwnedTlsFingerprintProfileProvider
    @Inject
    constructor(
        appSettingsRepository: AppSettingsRepository,
        @ApplicationIoScope scope: CoroutineScope,
    ) : OwnedTlsFingerprintProfileProvider {
        private val currentProfile = AtomicReference(TlsFingerprintProfileChromeStable)

        init {
            scope.launch {
                appSettingsRepository.settings
                    .map { settings -> normalizeTlsFingerprintProfile(settings.tlsFingerprintProfile) }
                    .collect(currentProfile::set)
            }
        }

        override fun currentProfile(): String = currentProfile.get()
    }

@Singleton
class DefaultOwnedTlsClientFactory
    @Inject
    constructor(
        private val profileProvider: OwnedTlsFingerprintProfileProvider,
        private val strategyPackStateStore: StrategyPackStateStore,
        @param:Named("ownedTlsSessionSeed") private val sessionSeed: Long,
    ) : OwnedTlsClientFactory,
        DiagnosticsHttpClientFactory {
        override fun currentProfile(): String = profileProvider.currentProfile()

        override fun selectionForAuthority(authority: String?): OwnedTlsFingerprintSelection {
            val runtimeState = strategyPackStateStore.state.value
            val allowedProfileIds = runtimeState.tlsProfileAllowedIds.map(::normalizeTlsFingerprintProfile).distinct()
            val defaultProfile = normalizeTlsFingerprintProfile(currentProfile())
            val selectedProfile =
                if (authority.isNullOrBlank() || !runtimeState.tlsRotationEnabled || allowedProfileIds.isEmpty()) {
                    defaultProfile
                } else {
                    deterministicTlsProfileSelection(
                        authority = authority,
                        sessionSeed = sessionSeed,
                        profileSetId = runtimeState.tlsProfileSetId ?: com.poyka.ripdpi.data.DefaultTlsProfileSetId,
                        allowedProfileIds = allowedProfileIds,
                        fallbackProfile = defaultProfile,
                    )
                }
            return OwnedTlsFingerprintSelection(
                profileId = selectedProfile,
                profileSetId = runtimeState.tlsProfileSetId ?: com.poyka.ripdpi.data.DefaultTlsProfileSetId,
                catalogVersion =
                    runtimeState.tlsProfileCatalogVersion ?: com.poyka.ripdpi.data.DefaultTlsProfileCatalogVersion,
            )
        }

        override fun create(
            forcedTlsVersions: List<TlsVersion>?,
            configure: OkHttpClient.Builder.() -> Unit,
        ): OkHttpClient =
            createForAuthority(
                authority = null,
                forcedTlsVersions = forcedTlsVersions,
                configure = configure,
            )

        override fun createClient(configure: OkHttpClient.Builder.() -> Unit): OkHttpClient =
            create(
                forcedTlsVersions = null,
                configure = configure,
            )

        override fun createForAuthority(
            authority: String?,
            forcedTlsVersions: List<TlsVersion>?,
            configure: OkHttpClient.Builder.() -> Unit,
        ): OkHttpClient =
            OkHttpClient
                .Builder()
                .apply(configure)
                .applyTlsFingerprintProfile(
                    profile = selectionForAuthority(authority).profileId,
                    forcedTlsVersions = forcedTlsVersions,
                ).build()
    }

@Suppress("SpreadOperator")
internal fun OkHttpClient.Builder.applyTlsFingerprintProfile(
    profile: String,
    forcedTlsVersions: List<TlsVersion>? = null,
): OkHttpClient.Builder {
    val normalizedProfile = normalizeTlsFingerprintProfile(profile)
    val specBuilder = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
    forcedTlsVersions
        ?.takeIf(List<TlsVersion>::isNotEmpty)
        ?.toTypedArray()
        ?.let(specBuilder::tlsVersions)

    val spec =
        when (normalizedProfile) {
            TlsFingerprintProfileChromeStable -> {
                specBuilder.cipherSuites(*chromeLikeCipherSuites()).build()
            }

            TlsFingerprintProfileFirefoxStable -> {
                specBuilder.cipherSuites(*firefoxLikeCipherSuites()).build()
            }

            TlsFingerprintProfileSafariStable -> {
                specBuilder.cipherSuites(*safariLikeCipherSuites()).build()
            }

            TlsFingerprintProfileEdgeStable -> {
                specBuilder.cipherSuites(*edgeLikeCipherSuites()).build()
            }

            else -> {
                if (forcedTlsVersions.isNullOrEmpty()) {
                    ConnectionSpec.MODERN_TLS
                } else {
                    specBuilder.build()
                }
            }
        }
    connectionSpecs(listOf(spec))
    return this
}

private fun chromeLikeCipherSuites(): Array<CipherSuite> =
    arrayOf(
        CipherSuite.TLS_AES_128_GCM_SHA256,
        CipherSuite.TLS_AES_256_GCM_SHA384,
        CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
    )

private fun firefoxLikeCipherSuites(): Array<CipherSuite> =
    arrayOf(
        CipherSuite.TLS_AES_128_GCM_SHA256,
        CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
    )

private fun safariLikeCipherSuites(): Array<CipherSuite> =
    arrayOf(
        CipherSuite.TLS_AES_128_GCM_SHA256,
        CipherSuite.TLS_AES_256_GCM_SHA384,
        CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
    )

private fun edgeLikeCipherSuites(): Array<CipherSuite> = chromeLikeCipherSuites()

private fun deterministicTlsProfileSelection(
    authority: String,
    sessionSeed: Long,
    profileSetId: String,
    allowedProfileIds: List<String>,
    fallbackProfile: String,
): String {
    val digest =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest("$authority|$sessionSeed|$profileSetId".toByteArray(Charsets.UTF_8))
    val candidateIndex =
        ((digest.firstOrNull()?.toInt() ?: 0) and 0xff) % allowedProfileIds.size.coerceAtLeast(1)
    return allowedProfileIds.getOrElse(candidateIndex) { fallbackProfile }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class OwnedTlsClientModule {
    @Binds
    @Singleton
    abstract fun bindOwnedTlsFingerprintProfileProvider(
        provider: SettingsBackedOwnedTlsFingerprintProfileProvider,
    ): OwnedTlsFingerprintProfileProvider

    @Binds
    @Singleton
    abstract fun bindOwnedTlsClientFactory(factory: DefaultOwnedTlsClientFactory): OwnedTlsClientFactory

    @Binds
    @Singleton
    abstract fun bindDiagnosticsHttpClientFactory(factory: DefaultOwnedTlsClientFactory): DiagnosticsHttpClientFactory
}

@dagger.Module
@dagger.hilt.InstallIn(SingletonComponent::class)
internal object OwnedTlsSeedModule {
    @dagger.Provides
    @Singleton
    @Named("ownedTlsSessionSeed")
    fun provideOwnedTlsSessionSeed(): Long = Random.nextLong()
}
