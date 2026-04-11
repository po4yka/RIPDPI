package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.core.service.BuildConfig
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayMasqueAuthModePrivacyPass
import com.poyka.ripdpi.data.normalizeRelayMasqueAuthMode
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

internal data class MasquePrivacyPassRuntimeConfig(
    val providerUrl: String,
    val providerAuthToken: String? = null,
)

internal data class MasquePrivacyPassProviderSettings(
    val providerUrl: String,
    val providerAuthToken: String? = null,
)

internal enum class MasquePrivacyPassReadiness {
    Ready,
    MissingProviderUrl,
    InvalidProviderUrl,
    UnsupportedRelayKind,
    UnsupportedAuthMode,
}

interface MasquePrivacyPassAvailability {
    fun isAvailable(): Boolean
}

internal interface MasquePrivacyPassProvider : MasquePrivacyPassAvailability {
    fun readinessFor(
        config: RipDpiRelayConfig,
        credentials: RelayCredentialRecord?,
    ): MasquePrivacyPassReadiness

    suspend fun resolve(
        profileId: String,
        config: RipDpiRelayConfig,
        credentials: RelayCredentialRecord?,
    ): MasquePrivacyPassRuntimeConfig?
}

@Singleton
internal class BuildConfigMasquePrivacyPassProvider
    @Inject
    constructor(
        private val settings: MasquePrivacyPassProviderSettings,
    ) : MasquePrivacyPassProvider {
        private val providerUrl = settings.providerUrl.trim()
        private val providerAuthToken = settings.providerAuthToken?.trim()?.ifBlank { null }

        override fun isAvailable(): Boolean = readinessForMasquePrivacyPass() == MasquePrivacyPassReadiness.Ready

        override fun readinessFor(
            config: RipDpiRelayConfig,
            credentials: RelayCredentialRecord?,
        ): MasquePrivacyPassReadiness {
            if (config.kind != RelayKindMasque) {
                return MasquePrivacyPassReadiness.UnsupportedRelayKind
            }
            val authMode =
                normalizeRelayMasqueAuthMode(
                    value = credentials?.masqueAuthMode,
                    cloudflareMode = config.masqueCloudflareMode,
                ) ?: return MasquePrivacyPassReadiness.UnsupportedAuthMode
            if (authMode != RelayMasqueAuthModePrivacyPass) {
                return MasquePrivacyPassReadiness.UnsupportedAuthMode
            }
            return readinessForMasquePrivacyPass()
        }

        override suspend fun resolve(
            profileId: String,
            config: RipDpiRelayConfig,
            credentials: RelayCredentialRecord?,
        ): MasquePrivacyPassRuntimeConfig? {
            if (readinessFor(config, credentials) != MasquePrivacyPassReadiness.Ready) {
                return null
            }
            return MasquePrivacyPassRuntimeConfig(
                providerUrl = providerUrl,
                providerAuthToken = providerAuthToken,
            )
        }

        private fun readinessForMasquePrivacyPass(): MasquePrivacyPassReadiness {
            if (providerUrl.isBlank()) {
                return MasquePrivacyPassReadiness.MissingProviderUrl
            }
            val parsed = runCatching { java.net.URI(providerUrl) }.getOrNull()
            val isSupportedScheme = parsed?.scheme in setOf("https", "http")
            val hasHost = !parsed?.host.isNullOrBlank()
            if (!isSupportedScheme || !hasHost) {
                return MasquePrivacyPassReadiness.InvalidProviderUrl
            }
            return MasquePrivacyPassReadiness.Ready
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal object MasquePrivacyPassProviderModule {
    @Provides
    @Singleton
    fun provideMasquePrivacyPassProviderSettings(): MasquePrivacyPassProviderSettings =
        MasquePrivacyPassProviderSettings(
            providerUrl = BuildConfig.MASQUE_PRIVACY_PASS_PROVIDER_URL,
            providerAuthToken = BuildConfig.MASQUE_PRIVACY_PASS_PROVIDER_AUTH_TOKEN,
        )

    @Provides
    @Singleton
    fun provideMasquePrivacyPassProvider(provider: BuildConfigMasquePrivacyPassProvider): MasquePrivacyPassProvider =
        provider

    @Provides
    @Singleton
    fun provideMasquePrivacyPassAvailability(
        provider: BuildConfigMasquePrivacyPassProvider,
    ): MasquePrivacyPassAvailability = provider
}
