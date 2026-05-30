package com.poyka.ripdpi.ui.screens.xray

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ApplicationScope
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.XrayImportParser
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayServiceModeOption
import com.poyka.ripdpi.platform.StringResolver
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The pinned xray-core upstream tag used for version-gated import validation.
 *
 * Mirrors the `xray-core` pin in `gradle/libs.versions.toml` (kept in the
 * `v<major>.<minor>.<patch>` shape [com.poyka.ripdpi.data.XrayConfigValidator]
 * compares against). When the pin moves, update this constant in the same PR
 * so the importer enforces the same version gate the binary ships with.
 */
const val PinnedXrayCoreUpstreamTag: String = "v26.4.7"

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
        @param:ApplicationScope private val scope: CoroutineScope,
    ) : XrayProfilePersistence {
        override val upstreamTag: String = PinnedXrayCoreUpstreamTag

        override val emptyInputMessage: String
            get() = stringResolver.getString(R.string.xray_import_empty_input_error)

        override fun persist(
            option: XrayServiceModeOption,
            profile: XrayProfile?,
        ) {
            selectionStore.record(option, profile)
            val mode = if (option == XrayServiceModeOption.NativeProxy) Mode.Proxy else Mode.VPN
            scope.launch {
                appSettingsRepository.update { setRipdpiMode(mode.preferenceValue) }
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class XrayProfilePersistenceModule {
    @Binds
    @Singleton
    abstract fun bindXrayProfilePersistence(impl: DefaultXrayProfilePersistence): XrayProfilePersistence

    companion object {
        /**
         * Provides the pure-Kotlin [XrayImportParser] the import ViewModel
         * injects. The parser has no `@Inject` constructor (it lives in the
         * Android-free `:core:data:catalog` module), so the binding is supplied
         * here with its default [com.poyka.ripdpi.data.xray.XrayConfigRenderer].
         */
        @Provides
        @Singleton
        fun provideXrayImportParser(): XrayImportParser = XrayImportParser()
    }
}
