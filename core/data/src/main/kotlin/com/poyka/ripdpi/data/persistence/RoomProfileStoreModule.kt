package com.poyka.ripdpi.data.persistence

import com.poyka.ripdpi.data.ProfileSelectionStore
import com.poyka.ripdpi.data.ProxyGroupBlobStore
import com.poyka.ripdpi.data.RelayCredentialRepository
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.WarpCredentialStore
import com.poyka.ripdpi.data.WarpEndpointStore
import com.poyka.ripdpi.data.WarpProfileStore
import com.poyka.ripdpi.data.awg.AwgCredentialStore
import com.poyka.ripdpi.data.selector.SelectorPersistence
import com.poyka.ripdpi.data.xray.XrayProfileMetadataStore
import com.poyka.ripdpi.data.xray.XrayProfileSecretStore
import com.poyka.ripdpi.data.xray.XrayProviderSelectionStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RoomProfileStoreModule {
    @Binds abstract fun relayProfiles(store: RoomRelayProfileStore): RelayProfileStore

    @Binds abstract fun relayCredentials(store: RoomRelayCredentialStore): RelayCredentialStore

    @Binds abstract fun relayCredentialRepository(store: RoomRelayCredentialStore): RelayCredentialRepository

    @Binds abstract fun warpProfiles(store: RoomWarpProfileStore): WarpProfileStore

    @Binds abstract fun warpCredentials(store: RoomWarpCredentialStore): WarpCredentialStore

    @Binds abstract fun warpEndpoints(store: RoomWarpEndpointStore): WarpEndpointStore

    @Binds abstract fun xrayMetadata(store: RoomXrayProfileMetadataStore): XrayProfileMetadataStore

    @Binds abstract fun xraySecrets(store: RoomXrayProfileSecretStore): XrayProfileSecretStore

    @Binds abstract fun awgCredentials(store: RoomAwgCredentialStore): AwgCredentialStore

    @Binds abstract fun awgReadStore(store: RoomAwgProfileReadStore): com.poyka.ripdpi.data.awg.AwgProfileReadStore

    @Binds abstract fun groups(store: RoomProxyGroupBlobStore): ProxyGroupBlobStore

    @Binds abstract fun selection(store: RoomProfileSelectionStore): ProfileSelectionStore

    @Binds abstract fun xraySelection(store: RoomXraySelectionStore): XrayProviderSelectionStore

    @Binds abstract fun selector(store: RoomSelectorPersistence): SelectorPersistence

    @Binds
    abstract fun xrayProfiles(store: RoomDurableXrayProfileStore): com.poyka.ripdpi.data.xray.DurableXrayProfileStore
}
