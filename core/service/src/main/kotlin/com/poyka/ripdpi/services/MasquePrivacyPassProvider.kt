package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.RelayCredentialRecord
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

internal data class MasquePrivacyPassRuntimeConfig(
    val providerUrl: String,
    val providerAuthToken: String? = null,
)

interface MasquePrivacyPassAvailability {
    fun isAvailable(): Boolean
}

internal interface MasquePrivacyPassProvider : MasquePrivacyPassAvailability {
    suspend fun resolve(
        profileId: String,
        config: RipDpiRelayConfig,
        credentials: RelayCredentialRecord?,
    ): MasquePrivacyPassRuntimeConfig?
}

@Singleton
internal class NoopMasquePrivacyPassProvider
    @Inject
    constructor() :
    MasquePrivacyPassProvider,
        MasquePrivacyPassAvailability {
        override fun isAvailable(): Boolean = false

        override suspend fun resolve(
            profileId: String,
            config: RipDpiRelayConfig,
            credentials: RelayCredentialRecord?,
        ): MasquePrivacyPassRuntimeConfig? = null
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class MasquePrivacyPassProviderModule {
    @Binds
    @Singleton
    abstract fun bindMasquePrivacyPassProvider(provider: NoopMasquePrivacyPassProvider): MasquePrivacyPassProvider

    @Binds
    @Singleton
    abstract fun bindMasquePrivacyPassAvailability(
        provider: NoopMasquePrivacyPassProvider,
    ): MasquePrivacyPassAvailability
}
