package com.poyka.ripdpi.ui.screens.xray

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ProfileMutationCoordinator
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.XrayProviderMutationCoordinator
import com.poyka.ripdpi.data.xray.DefaultXrayProfileId
import com.poyka.ripdpi.data.xray.DurableXrayProfileStore
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProviderSelectionRecord
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
            storedXrayProfile: XrayProfile? = profile,
        ) {
            _selection.value =
                XrayProviderSelection(
                    option = option,
                    acceptedProfile = profile,
                    storedXrayProfile = storedXrayProfile,
                )
        }
    }

/**
 * @property option the chosen service-mode option (provider axis).
 * @property acceptedProfile the validated Xray profile, when [option] is the
 *   active Xray provider; null for native options.
 * @property storedXrayProfile the last durable/imported Xray profile available
 *   for switching back to the Xray provider without re-importing.
 */
data class XrayProviderSelection(
    val option: XrayServiceModeOption = XrayServiceModeOption.default,
    val acceptedProfile: XrayProfile? = null,
    val storedXrayProfile: XrayProfile? = acceptedProfile,
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
 * into the process-scoped [XrayProviderSelectionStore] the UI layer observes.
 *
 * For the Xray provider it ALSO writes the durable production source the
 * `:core:service` libXray runner reads: the validated profile goes to the
 * Keystore-split [DurableXrayProfileStore] and the durable
 * [com.poyka.ripdpi.data.xray.XrayProviderSelectionStore] is flipped to Xray.
 * The native relay is then NOT activated — the libXray runner owns that
 * connection. The native options flip the durable selection to native while
 * preserving the durable Xray profile for an explicit future switch back, then
 * activate the relay exactly as before.
 */
interface XrayNativeProviderSelection {
    suspend fun selectNativeMode(mode: Mode)
}

class DefaultXrayProfilePersistence
    @Inject
    constructor(
        private val appSettingsRepository: AppSettingsRepository,
        private val selectionStore: XrayProviderSelectionStore,
        private val stringResolver: StringResolver,
        private val relayActivator: NativeRelayProfileActivator,
        private val durableProfileStore: DurableXrayProfileStore,
        private val durableSelectionStore: com.poyka.ripdpi.data.xray.XrayProviderSelectionStore,
        private val profileMutations: ProfileMutationCoordinator,
        private val xrayProviderMutations: XrayProviderMutationCoordinator,
    ) : XrayProfilePersistence,
        XrayNativeProviderSelection {
        override val emptyInputMessage: String
            get() = stringResolver.getString(R.string.xray_import_empty_input_error)

        override val noSupportedNodesMessage: String
            get() = stringResolver.getString(R.string.xray_import_error_no_supported)

        override val unparseableMessage: String
            get() = stringResolver.getString(R.string.xray_import_error_unparseable)

        override val persistFailedMessage: String
            get() = stringResolver.getString(R.string.xray_import_error_activation_failed)

        override val restoreFailedMessage: String
            get() = stringResolver.getString(R.string.xray_import_error_restore_failed)

        override val unsupportedTypedProfileMessage: String
            get() = stringResolver.getString(R.string.xray_import_error_typed_unsupported)

        override val missingTypedProfileFieldMessage: String
            get() = stringResolver.getString(R.string.xray_import_error_typed_missing_field)

        override val unsafeTypedProfileMessage: String
            get() = stringResolver.getString(R.string.xray_import_error_typed_unsafe)

        override suspend fun restoreSelection(): XrayProviderSelection =
            profileMutations
                .readRecovered {
                    val durableSelection = durableSelectionStore.current()
                    val storedProfile = durableProfileStore.load(DefaultXrayProfileId)
                    val option =
                        when (durableSelection.kind) {
                            VpnProviderKind.Xray -> XrayServiceModeOption.XrayVpn
                            VpnProviderKind.Native -> nativeOptionFromSettings()
                        }
                    val activeProfile =
                        if (option.requiresXrayProfile) {
                            durableSelection.activeProfileId
                                .takeIf { it.isNotBlank() }
                                ?.let { profileId ->
                                    if (profileId == DefaultXrayProfileId) {
                                        storedProfile
                                    } else {
                                        durableProfileStore.load(profileId)
                                    }
                                }
                        } else {
                            null
                        }
                    XrayProviderSelection(
                        option = option,
                        acceptedProfile = activeProfile,
                        storedXrayProfile = storedProfile ?: activeProfile,
                    )
                }.also { restored ->
                    selectionStore.record(restored.option, restored.acceptedProfile, restored.storedXrayProfile)
                }

        override suspend fun persist(
            option: XrayServiceModeOption,
            profiles: List<ProxyProfile>,
            acceptedProfile: XrayProfile?,
        ) {
            if (option.requiresXrayProfile) {
                // The Xray option can only run via the libXray runner, which needs a
                // typed VLESS/REALITY profile. Fail-closed when one is absent rather
                // than persisting a half/empty profile or silently falling back to a
                // native relay — the ViewModel surfaces this as persistFailedMessage.
                val profile =
                    requireNotNull(acceptedProfile) {
                        "Xray provider selected without a validated profile"
                    }
                xrayProviderMutations.upsertXrayProvider(
                    profileId = DefaultXrayProfileId,
                    profile = profile,
                    selection =
                        XrayProviderSelectionRecord(
                            providerKind = XrayProviderSelectionRecord.ProviderKindXray,
                            activeProfileId = DefaultXrayProfileId,
                        ),
                    modeAfterImage = Mode.VPN.preferenceValue,
                )
                selectionStore.record(option, profile)
                // libXray owns the connection: no native relay activation.
                return
            }
            persistNativeOption(option, profiles)
        }

        override suspend fun selectNativeMode(mode: Mode) {
            val option =
                if (mode == Mode.Proxy) {
                    XrayServiceModeOption.NativeProxy
                } else {
                    XrayServiceModeOption.NativeDirect
                }
            persistNativeOption(option, emptyList())
        }

        private suspend fun persistNativeOption(
            option: XrayServiceModeOption,
            profiles: List<ProxyProfile>,
        ) {
            // Native options: clear only the active provider selection so the service
            // layer does not branch onto the libXray runner. Keep the durable Xray
            // profile available for an explicit switch back/recreated ViewModel.
            val storedProfile = profileMutations.readRecovered { durableProfileStore.load(DefaultXrayProfileId) }
            val mode = if (option == XrayServiceModeOption.NativeProxy) Mode.Proxy else Mode.VPN
            val nativeSelection =
                XrayProviderSelectionRecord(
                    providerKind = XrayProviderSelectionRecord.ProviderKindNative,
                    activeProfileId = "",
                )
            val nativeProfile = profiles.firstOrNull { relayActivator.supports(it) }
            if (nativeProfile == null) {
                xrayProviderMutations.selectNativeProvider(
                    selection = nativeSelection,
                    modeAfterImage = mode.preferenceValue,
                )
            } else {
                relayActivator.activate(
                    profile = nativeProfile,
                    modeAfterImage = mode.preferenceValue,
                    xraySelectionAfterImage = nativeSelection,
                )
            }
            selectionStore.record(option, null, storedProfile)
        }

        private suspend fun nativeOptionFromSettings(): XrayServiceModeOption =
            if (appSettingsRepository.snapshot().ripdpiMode == Mode.Proxy.preferenceValue) {
                XrayServiceModeOption.NativeProxy
            } else {
                XrayServiceModeOption.NativeDirect
            }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class XrayProfilePersistenceModule {
    @Binds
    @Singleton
    abstract fun bindXrayProfilePersistence(impl: DefaultXrayProfilePersistence): XrayProfilePersistence

    @Binds
    @Singleton
    abstract fun bindXrayNativeProviderSelection(impl: DefaultXrayProfilePersistence): XrayNativeProviderSelection
}
