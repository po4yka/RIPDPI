package com.poyka.ripdpi.ui.screens.xray

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayServiceModeOption
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.ui.screens.proxyimport.NativeRelayProfileActivator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime selection produced by the Xray provider-selection / import surface.
 *
 * Holds the user's chosen service-mode option and, for the Xray provider, the
 * accepted (validated) [XrayProfile]. This is an in-memory, process-scoped
 * holder: it is intentionally NOT serialised to the proto settings store here,
 * because the durable Xray-provider persistence schema is owned by the
 * `:core:service` provider-selection work (tasks 1-4) rather than this UX task.
 *
 * The accepted profile is kept in memory only and never logged; any user-facing
 * surface goes through [com.poyka.ripdpi.data.xray.XrayProfileRedactor].
 */
@Singleton
class XrayProviderSelectionStore
    @Inject
    constructor() {
        private val _selection = MutableStateFlow(XrayProviderSelection())
        val selection: StateFlow<XrayProviderSelection> = _selection.asStateFlow()

        fun record(
            option: XrayServiceModeOption,
            profile: XrayProfile?,
        ) {
            _selection.value = XrayProviderSelection(option = option, acceptedProfile = profile)
        }
    }

/**
 * @property option the chosen service-mode option (provider axis).
 * @property acceptedProfile the validated Xray profile, when [option] is the
 *   Xray provider; null for native options.
 */
data class XrayProviderSelection(
    val option: XrayServiceModeOption = XrayServiceModeOption.default,
    val acceptedProfile: XrayProfile? = null,
) {
    /** The provider kind the selection resolves to. */
    val providerKind: VpnProviderKind get() = option.providerKind
}

/**
 * Default [XrayProfilePersistence] backing the import ViewModel.
 *
 * Persists the *runtime mode* axis (`vpn`/`proxy`) durably through
 * [AppSettingsRepository] — every offered option is a full-tunnel VPN-capable
 * mode, so the native proxy option maps to [Mode.Proxy] and the rest to
 * [Mode.VPN] — and records the full provider selection (+ accepted profile)
 * into the process-scoped [XrayProviderSelectionStore] the service layer reads.
 *
 * The settings write is fire-and-forget on the injected [scope] so the
 * ViewModel's `confirm()` stays synchronous and free of the suspend boundary.
 */
@Singleton
class DefaultXrayProfilePersistence
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val selectionStore: XrayProviderSelectionStore,
        private val stringResolver: StringResolver,
        private val relayActivator: NativeRelayProfileActivator,
    ) : XrayProfilePersistence {
        override val emptyInputMessage: String
            get() = stringResolver.getString(R.string.xray_import_empty_input_error)

        override val noSupportedNodesMessage: String
            get() = stringResolver.getString(R.string.xray_import_error_no_supported)

        override val unparseableMessage: String
            get() = stringResolver.getString(R.string.xray_import_error_unparseable)

        override val persistFailedMessage: String
            get() = stringResolver.getString(R.string.xray_import_error_activation_failed)

        override suspend fun persist(
            option: XrayServiceModeOption,
            profiles: List<ProxyProfile>,
        ) {
            // The libXray provider path is not yet linked; the accepted profile is
            // not retained. Record the chosen mode and activate the first
            // natively-supported translated outbound on the native relay engine.
            selectionStore.record(option, null)
            val mode = if (option == XrayServiceModeOption.NativeProxy) Mode.Proxy else Mode.VPN
            profiles.firstOrNull { relayActivator.supports(it) }?.let { relayActivator.activate(it) }
            appSettingsRepository.update { setRipdpiMode(mode.preferenceValue) }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class XrayProfilePersistenceModule {
    @Binds
    @Singleton
    abstract fun bindXrayProfilePersistence(impl: DefaultXrayProfilePersistence): XrayProfilePersistence
}
