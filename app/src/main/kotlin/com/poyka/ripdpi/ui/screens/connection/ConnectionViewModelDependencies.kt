package com.poyka.ripdpi.ui.screens.connection

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.LatestDirectModeOutcomeStore
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.RelayPresetCatalog
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.security.MasqueClientCredentialImporter
import com.poyka.ripdpi.services.MasquePrivacyPassAvailability
import com.poyka.ripdpi.services.NaiveProxyProfileCheck
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.ui.screens.xray.XrayNativeProviderSelection
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

class ConnectionViewModelDependencies
    @Inject
    internal constructor(
        val appSettingsRepository: AppSettingsRepository,
        val relayArtifacts: ConnectionRelayArtifactRepository,
        val relayPresetCatalog: RelayPresetCatalog,
        val networkSnapshotProvider: NativeNetworkSnapshotProvider,
        val serviceStateStore: ServiceStateStore,
        val serviceController: ServiceController,
        val reconnectCoordinator: com.poyka.ripdpi.services.RunningServiceReconnect,
        val latestDirectModeOutcomeStore: LatestDirectModeOutcomeStore,
        val capabilityObserver: ConnectionCapabilityObserver,
        val dispatchers: AppCoroutineDispatchers,
        internal val editorDraftStore: ConnectionEditorDraftStore,
        val xrayNativeProviderSelection: XrayNativeProviderSelection,
        internal val externalProviders: ConnectionExternalProviders,
        // A reconnect outlives this screen: cancelling it midway would leave the service stopped.
        @param:ApplicationScope val applicationScope: CoroutineScope,
    )

class ConnectionImportDependencies
    @Inject
    constructor(
        val masqueClientCredentialImporter: MasqueClientCredentialImporter,
        val masquePrivacyPassAvailability: MasquePrivacyPassAvailability,
        val naiveProxyProfileCheck: NaiveProxyProfileCheck,
    )
