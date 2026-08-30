package com.poyka.ripdpi.backup

import com.poyka.ripdpi.data.KeystoreRelayCredentialStore
import com.poyka.ripdpi.data.KeystoreWarpCredentialStore
import com.poyka.ripdpi.data.KeystoreWsTunnelWorkerCredentialStore
import com.poyka.ripdpi.data.SharedPreferencesRelayProfileStore
import com.poyka.ripdpi.data.SharedPreferencesWarpEndpointStore
import com.poyka.ripdpi.data.SharedPreferencesWarpProfileStore
import com.poyka.ripdpi.data.awg.AwgProfileDao
import com.poyka.ripdpi.data.awg.KeystoreAwgCredentialStore
import com.poyka.ripdpi.data.boot.BootSessionStateStore
import com.poyka.ripdpi.data.selector.SharedPreferencesSelectorSelectionStore
import com.poyka.ripdpi.data.xray.KeystoreXrayProfileSecretStore
import com.poyka.ripdpi.data.xray.SharedPreferencesXrayProfileMetadataStore
import com.poyka.ripdpi.data.xray.SharedPreferencesXrayProviderSelectionStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Clears every durable user profile, credential, selection, and boot-session pointer. */
fun interface UserProfileResetStore {
    suspend fun clearAll()
}

internal fun interface RelayUserDataResetter {
    suspend fun clearAll()
}

internal fun interface WarpUserDataResetter {
    suspend fun clearAll()
}

internal fun interface AwgUserDataResetter {
    suspend fun clearAll()
}

internal fun interface XrayUserDataResetter {
    suspend fun clearAll()
}

internal fun interface SessionUserDataResetter {
    suspend fun clearAll()
}

@Singleton
internal class DefaultUserProfileResetStore
    @Inject
    constructor(
        private val relay: RelayUserDataResetter,
        private val warp: WarpUserDataResetter,
        private val awg: AwgUserDataResetter,
        private val xray: XrayUserDataResetter,
        private val session: SessionUserDataResetter,
    ) : UserProfileResetStore {
        override suspend fun clearAll() {
            relay.clearAll()
            warp.clearAll()
            awg.clearAll()
            xray.clearAll()
            session.clearAll()
        }
    }

@Module
@InstallIn(SingletonComponent::class)
internal abstract class UserProfileResetStoreModule {
    @Binds
    @Singleton
    abstract fun bindUserProfileResetStore(store: DefaultUserProfileResetStore): UserProfileResetStore
}

@Module
@InstallIn(SingletonComponent::class)
internal object UserProfileResetActionsModule {
    @dagger.Provides
    @Singleton
    fun provideRelayUserDataResetter(
        profiles: SharedPreferencesRelayProfileStore,
        credentials: KeystoreRelayCredentialStore,
    ): RelayUserDataResetter =
        RelayUserDataResetter {
            credentials.clearAll()
            profiles.clearAll()
        }

    @dagger.Provides
    @Singleton
    fun provideWarpUserDataResetter(
        profiles: SharedPreferencesWarpProfileStore,
        credentials: KeystoreWarpCredentialStore,
        endpoints: SharedPreferencesWarpEndpointStore,
    ): WarpUserDataResetter =
        WarpUserDataResetter {
            credentials.clearAll()
            endpoints.clearAll()
            profiles.clearAll()
        }

    @dagger.Provides
    @Singleton
    fun provideAwgUserDataResetter(
        profiles: AwgProfileDao,
        credentials: KeystoreAwgCredentialStore,
    ): AwgUserDataResetter =
        AwgUserDataResetter {
            credentials.clearAll()
            profiles.deleteAll()
        }

    @dagger.Provides
    @Singleton
    fun provideXrayUserDataResetter(
        metadata: SharedPreferencesXrayProfileMetadataStore,
        credentials: KeystoreXrayProfileSecretStore,
        selection: SharedPreferencesXrayProviderSelectionStore,
    ): XrayUserDataResetter =
        XrayUserDataResetter {
            credentials.clearAll()
            metadata.clearAll()
            selection.clear()
        }

    @dagger.Provides
    @Singleton
    fun provideSessionUserDataResetter(
        selections: SharedPreferencesSelectorSelectionStore,
        bootSession: BootSessionStateStore,
        workerTransport: KeystoreWsTunnelWorkerCredentialStore,
    ): SessionUserDataResetter =
        SessionUserDataResetter {
            selections.clearAll()
            bootSession.clearAll()
            workerTransport.clearAll()
        }
}
