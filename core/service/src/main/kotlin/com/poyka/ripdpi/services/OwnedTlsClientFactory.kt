package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ApplicationIoScope
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import com.poyka.ripdpi.data.TlsFingerprintProfileFirefoxStable
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
import javax.inject.Singleton

interface OwnedTlsFingerprintProfileProvider {
    fun currentProfile(): String
}

interface OwnedTlsClientFactory {
    fun currentProfile(): String

    fun create(
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
    ) : OwnedTlsClientFactory {
        override fun currentProfile(): String = profileProvider.currentProfile()

        override fun create(
            forcedTlsVersions: List<TlsVersion>?,
            configure: OkHttpClient.Builder.() -> Unit,
        ): OkHttpClient =
            OkHttpClient
                .Builder()
                .apply(configure)
                .applyTlsFingerprintProfile(
                    profile = currentProfile(),
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
}
