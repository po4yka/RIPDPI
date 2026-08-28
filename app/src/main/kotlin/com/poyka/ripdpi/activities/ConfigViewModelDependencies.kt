package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppCoroutineDispatchers
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.LatestDirectModeOutcomeStore
import com.poyka.ripdpi.data.NativeNetworkSnapshotProvider
import com.poyka.ripdpi.data.RelayPresetCatalog
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.security.MasqueClientCredentialImporter
import com.poyka.ripdpi.services.MasquePrivacyPassAvailability
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.ui.screens.xray.XrayNativeProviderSelection
import javax.inject.Inject

class ConfigViewModelDependencies
    @Inject
    internal constructor(
        val appSettingsRepository: AppSettingsRepository,
        val relayArtifacts: ConfigRelayArtifactRepository,
        val relayPresetCatalog: RelayPresetCatalog,
        val networkSnapshotProvider: NativeNetworkSnapshotProvider,
        val serviceStateStore: ServiceStateStore,
        val serviceController: ServiceController,
        val latestDirectModeOutcomeStore: LatestDirectModeOutcomeStore,
        val capabilityObserver: ConfigCapabilityObserver,
        val dispatchers: AppCoroutineDispatchers,
        internal val editorDraftStore: ConfigEditorDraftStore,
        val xrayNativeProviderSelection: XrayNativeProviderSelection,
    )

class ConfigImportDependencies
    @Inject
    constructor(
        val masqueClientCredentialImporter: MasqueClientCredentialImporter,
        val masquePrivacyPassAvailability: MasquePrivacyPassAvailability,
    )
