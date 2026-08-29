package com.poyka.ripdpi.ui.screens.xray

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.subscription.XrayConfigImportParser
import com.poyka.ripdpi.data.subscription.XrayConfigImportResult
import com.poyka.ripdpi.data.subscription.XraySkipReason
import com.poyka.ripdpi.data.subscription.XraySkippedNode
import com.poyka.ripdpi.data.xray.VpnProviderKind
import com.poyka.ripdpi.data.xray.XrayCapability
import com.poyka.ripdpi.data.xray.XrayImportParser
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayProviderBuildInfo
import com.poyka.ripdpi.data.xray.XrayServiceModeOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * UI state for the Xray provider selection + profile import surface.
 *
 * @property selectedOption the currently chosen service-mode (provider) option.
 * @property rawInput the share link / config text the user is editing.
 * @property validating true while a parse/validate pass is in flight.
 * @property acceptedConfigReady true when at least one outbound translated to a
 *   native relay profile and the user can finish.
 * @property importableCount number of outbounds that translated to native profiles.
 * @property capabilities jargon-free capability labels for the translated config.
 * @property skipped outbounds that could not be translated, each with a reason.
 * @property errorMessage a redacted, user-safe validation error (null when none).
 */
enum class XrayImportRestoreStatus {
    Loading,
    Ready,
    Failed,
}

data class XrayImportUiState(
    val selectedOption: XrayServiceModeOption = XrayServiceModeOption.default,
    val rawInput: String = "",
    val restoreStatus: XrayImportRestoreStatus = XrayImportRestoreStatus.Loading,
    val restoreErrorMessage: String? = null,
    val validating: Boolean = false,
    val persisting: Boolean = false,
    val acceptedConfigReady: Boolean = false,
    val importableCount: Int = 0,
    val capabilities: ImmutableList<XrayCapability> = persistentListOf(),
    val skipped: ImmutableList<XraySkippedNode> = persistentListOf(),
    val errorMessage: String? = null,
) {
    /** True when the chosen option needs a validated Xray profile to proceed. */
    val requiresXrayProfile: Boolean get() = selectedOption.requiresXrayProfile

    /** User edits are blocked only while a confirmed persist is in flight. */
    val canEdit: Boolean get() = !persisting

    /** True when the user can finish after durable state has been read successfully. */
    val canFinish: Boolean
        get() =
            restoreStatus == XrayImportRestoreStatus.Ready &&
                !persisting &&
                (!requiresXrayProfile || acceptedConfigReady)
}

/**
 * Backs the Xray provider-selection + import screen and the onboarding
 * "validate the chosen mode" step.
 *
 * Import does NOT run xray-core. The pasted Xray config / share link is parsed by
 * [XrayConfigImportParser] and each supported outbound is TRANSLATED to a native
 * RIPDPI relay [ProxyProfile]; unsupported outbounds (vmess removed per ADR 0004,
 * plain VLESS without REALITY, utility / unknown protocols) are surfaced as
 * [XrayImportUiState.skipped] with a per-node reason. On confirm the translated
 * profiles are handed to the [XrayProfilePersistence] seam, which activates the
 * first one on the existing native relay engine — so the import produces a real,
 * runnable connection rather than a dead end.
 *
 * This ViewModel owns only UI-state transitions and keeps the translated profiles
 * (which carry secrets) in a private field, never in the observable UI state.
 */
@HiltViewModel
class XrayProfileImportViewModel
    @Inject
    constructor(
        private val persistence: XrayProfilePersistence,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(XrayImportUiState())
        val uiState: StateFlow<XrayImportUiState> = _uiState.asStateFlow()
        private val importedEventChannel = Channel<Unit>(capacity = Channel.BUFFERED)
        val importedEvents: Flow<Unit> = importedEventChannel.receiveAsFlow()
        private var completed = false

        /** True after the user edits selection/input; prevents late restore from overwriting fresh UI intent. */
        private var userEdited = false

        /** Monotonic edit token used to keep stale async restore results out of private caches. */
        private var interactionGeneration = 0L

        /** Profiles translated by the last successful [validate]; not exposed to the UI. */
        private var translatedProfiles: List<ProxyProfile> = emptyList()

        /**
         * The typed Xray profile the libXray provider runs, produced by the
         * validated [XrayImportParser] (render + [com.poyka.ripdpi.data.XrayConfigValidator]
         * gate) — NOT hand-converted from the translated relay profile. Null when
         * the chosen option is native, when the parser rejects input, or when the
         * temporary nullable Accepted API violates the expected typed-profile
         * contract. Never exposed to the UI and never logged.
         */
        private var acceptedXrayProfile: XrayProfile? = null

        /** Last durable/imported Xray profile kept available across native-provider toggles. */
        private var storedXrayProfile: XrayProfile? = null

        /**
         * Validated import parser: the same render + validator gate the libXray
         * runner reads through ([com.poyka.ripdpi.data.xray.XrayConfigRenderer] ->
         * [com.poyka.ripdpi.data.XrayConfigValidator]). The durable typed profile
         * is derived from its [XrayImportParser.Result.Accepted] output so an
         * import can never persist an unvalidated/incomplete REALITY profile.
         */
        private val xrayImportParser = XrayImportParser()

        /** Guards [confirm] against re-entry while the async persist is in flight. */
        private var importInFlight: Boolean = false

        init {
            restoreSelection()
        }

        private fun restoreSelection() {
            val restoreInteractionGeneration = interactionGeneration
            _uiState.update {
                it.copy(
                    restoreStatus = XrayImportRestoreStatus.Loading,
                    restoreErrorMessage = null,
                    errorMessage = null,
                )
            }
            viewModelScope.launch {
                val selection =
                    runCatching { persistence.restoreSelection() }
                        .getOrElse { error ->
                            if (error is CancellationException) throw error
                            _uiState.update { current ->
                                current.copy(
                                    restoreStatus = XrayImportRestoreStatus.Failed,
                                    restoreErrorMessage = persistence.restoreFailedMessage,
                                    validating = false,
                                    persisting = false,
                                    errorMessage = null,
                                )
                            }
                            return@launch
                        }
                if (restoreInteractionGeneration != interactionGeneration || userEdited) {
                    _uiState.update {
                        it.copy(
                            restoreStatus = XrayImportRestoreStatus.Ready,
                            restoreErrorMessage = null,
                            validating = false,
                            errorMessage = null,
                        )
                    }
                    return@launch
                }

                storedXrayProfile = selection.storedXrayProfile
                translatedProfiles = emptyList()
                val restoredProfile =
                    selection.acceptedProfile.takeIf {
                        selection.providerKind == VpnProviderKind.Xray
                    }
                acceptedXrayProfile = restoredProfile
                _uiState.update {
                    it.copy(
                        selectedOption = selection.option,
                        restoreStatus = XrayImportRestoreStatus.Ready,
                        restoreErrorMessage = null,
                        validating = false,
                        acceptedConfigReady = restoredProfile != null,
                        importableCount = if (restoredProfile == null) 0 else 1,
                        capabilities = restoredProfile?.let(::capabilitiesFor)?.toImmutableList() ?: persistentListOf(),
                        skipped = persistentListOf(),
                        errorMessage = null,
                    )
                }
            }
        }

        fun retryRestore() {
            if (_uiState.value.persisting) return
            completed = false
            restoreSelection()
        }

        /** Selects a service-mode option, clearing any stale validation outcome. */
        fun selectOption(option: XrayServiceModeOption) {
            if (!_uiState.value.canEdit) return
            userEdited = true
            interactionGeneration += 1
            completed = false
            translatedProfiles = emptyList()
            val restoredProfile = storedXrayProfile.takeIf { option.requiresXrayProfile }
            acceptedXrayProfile = restoredProfile
            _uiState.update {
                it.copy(
                    selectedOption = option,
                    rawInput = if (restoredProfile == null) it.rawInput else "",
                    acceptedConfigReady = restoredProfile != null,
                    importableCount = if (restoredProfile == null) 0 else 1,
                    capabilities = restoredProfile?.let(::capabilitiesFor)?.toImmutableList() ?: persistentListOf(),
                    skipped = persistentListOf(),
                    errorMessage = null,
                )
            }
        }

        /** Updates the editable import text, clearing the previous outcome. */
        fun onRawInputChange(value: String) {
            if (!_uiState.value.canEdit) return
            userEdited = true
            interactionGeneration += 1
            completed = false
            translatedProfiles = emptyList()
            acceptedXrayProfile = null
            _uiState.update {
                it.copy(
                    rawInput = value,
                    acceptedConfigReady = false,
                    importableCount = 0,
                    capabilities = persistentListOf(),
                    skipped = persistentListOf(),
                    errorMessage = null,
                )
            }
        }

        /**
         * Parses the current [XrayImportUiState.rawInput] and translates every
         * natively-supported outbound to a relay profile. On success the importable
         * count, capability labels, and any per-node skip reasons are exposed; on
         * failure a redacted [XrayImportUiState.errorMessage] is set and no profile is
         * retained (fail-closed).
         */
        fun validate() {
            val state = _uiState.value
            if (!state.canEdit || state.validating) return
            val input = state.rawInput.trim()
            if (input.isEmpty()) {
                _uiState.update { it.copy(errorMessage = persistence.emptyInputMessage) }
                return
            }
            _uiState.update { it.copy(validating = true, errorMessage = null) }
            if (state.requiresXrayProfile) {
                validateXrayProfile(input)
            } else {
                validateNativeRelayProfile(input)
            }
        }

        private fun validateNativeRelayProfile(input: String) {
            val groupId = UUID.randomUUID().toString()
            when (val result = XrayConfigImportParser.parse(input, groupId)) {
                is XrayConfigImportResult.Translated -> {
                    applyTranslation(result)
                }

                is XrayConfigImportResult.Unparseable -> {
                    failUnparseable()
                }
            }
        }

        private fun validateXrayProfile(input: String) {
            translatedProfiles = emptyList()
            val parsed =
                xrayImportParser.parse(
                    input,
                    upstreamTag = XrayProviderBuildInfo.upstreamTag,
                    profileName = IMPORTED_PROFILE_NAME,
                )
            when (parsed) {
                is XrayImportParser.Result.Accepted -> {
                    val typed = parsed.profile
                    acceptedXrayProfile = typed
                    storedXrayProfile = typed
                    _uiState.update {
                        it.copy(
                            validating = false,
                            acceptedConfigReady = true,
                            importableCount = 1,
                            capabilities = parsed.capabilities.toImmutableList(),
                            skipped = persistentListOf(),
                            errorMessage = null,
                        )
                    }
                }

                is XrayImportParser.Result.Rejected -> {
                    failXrayValidation(emptyList(), messageFor(parsed.reason))
                }
            }
        }

        private fun failUnparseable() {
            translatedProfiles = emptyList()
            acceptedXrayProfile = null
            _uiState.update {
                it.copy(
                    validating = false,
                    acceptedConfigReady = false,
                    importableCount = 0,
                    capabilities = persistentListOf(),
                    skipped = persistentListOf(),
                    errorMessage = persistence.unparseableMessage,
                )
            }
        }

        private fun applyTranslation(result: XrayConfigImportResult.Translated) {
            translatedProfiles = result.profiles
            acceptedXrayProfile = null
            if (result.profiles.isEmpty()) {
                // Valid config but every node was skipped — surface the reasons.
                _uiState.update {
                    it.copy(
                        validating = false,
                        acceptedConfigReady = false,
                        importableCount = 0,
                        capabilities = persistentListOf(),
                        skipped = result.skipped.toImmutableList(),
                        errorMessage = persistence.noSupportedNodesMessage,
                    )
                }
                return
            }
            // RIPDPI runs a single relay: the first supported node becomes the live
            // relay; the remaining supported nodes are surfaced as skipped (not
            // silently dropped) so every node is accounted for.
            val activated = result.profiles.first()
            val deferred =
                result.profiles.drop(1).mapIndexed { offset, profile ->
                    XraySkippedNode(
                        index = result.skipped.size + offset,
                        label = profile.displayName,
                        reason = XraySkipReason.SINGLE_RELAY_ONLY,
                    )
                }
            // Native options need no typed Xray profile.
            acceptedXrayProfile = null
            _uiState.update {
                it.copy(
                    validating = false,
                    acceptedConfigReady = true,
                    importableCount = 1,
                    capabilities = capabilitiesFor(listOf(activated)).toImmutableList(),
                    skipped = (result.skipped + deferred).toImmutableList(),
                    errorMessage = null,
                )
            }
        }

        private fun messageFor(reason: XrayImportParser.Reason): String =
            when (reason) {
                XrayImportParser.Reason.UNRECOGNISED_INPUT -> persistence.unparseableMessage
                XrayImportParser.Reason.UNSUPPORTED_TRANSPORT -> persistence.unsupportedTypedProfileMessage
                XrayImportParser.Reason.MISSING_REQUIRED_FIELD -> persistence.missingTypedProfileFieldMessage
                XrayImportParser.Reason.FAILED_SAFETY_CHECK -> persistence.unsafeTypedProfileMessage
            }

        /** Fail-closed Xray validate outcome: no typed profile, Finish disabled. */
        private fun failXrayValidation(
            skipped: List<XraySkippedNode>,
            message: String,
        ) {
            acceptedXrayProfile = null
            _uiState.update {
                it.copy(
                    validating = false,
                    acceptedConfigReady = false,
                    importableCount = 0,
                    capabilities = persistentListOf(),
                    skipped = skipped.toImmutableList(),
                    errorMessage = message,
                )
            }
        }

        /**
         * Hands the translated profiles to the persistence seam (which activates the
         * first supported one on the native relay) and records the chosen mode. Awaits
         * the persist so the navigate-away event is sent only once the relay is actually written. No-op when the
         * selection cannot finish yet or a persist is already in flight.
         */
        fun confirm() {
            val state = _uiState.value
            if (!state.canFinish || importInFlight || completed) return
            val payload =
                XrayPersistPayload(
                    option = state.selectedOption,
                    profiles = translatedProfiles.toList(),
                    acceptedProfile = acceptedXrayProfile,
                )
            importInFlight = true
            _uiState.update { it.copy(persisting = true, errorMessage = null) }
            viewModelScope.launch {
                val result =
                    runCatching {
                        persistence.persist(payload.option, payload.profiles, payload.acceptedProfile)
                    }
                importInFlight = false
                result.fold(
                    onSuccess = {
                        completed = true
                        importedEventChannel.send(Unit)
                    },
                    onFailure = { error ->
                        // Don't swallow structured-concurrency cancellation.
                        if (error is CancellationException) throw error
                        // Activation failed (store/settings I/O): surface a retryable error
                        // instead of crashing the scope or wedging on a dead Finish button.
                        _uiState.update {
                            it.copy(
                                persisting = false,
                                errorMessage = persistence.persistFailedMessage,
                            )
                        }
                    },
                )
            }
        }

        private data class XrayPersistPayload(
            val option: XrayServiceModeOption,
            val profiles: List<ProxyProfile>,
            val acceptedProfile: XrayProfile?,
        )

        private fun capabilitiesFor(profiles: List<ProxyProfile>): List<XrayCapability> =
            buildList {
                add(XrayCapability.VPN_PRIVACY)
                add(XrayCapability.RELAY)
                if (profiles.any { it is ProxyProfile.VlessReality }) add(XrayCapability.ANTI_DPI)
                add(XrayCapability.DNS_PROTECTION)
                add(XrayCapability.REALTIME_MEDIA)
            }

        private fun capabilitiesFor(profile: XrayProfile): List<XrayCapability> =
            buildList {
                add(XrayCapability.VPN_PRIVACY)
                add(XrayCapability.RELAY)
                if (profile.outbound.security == XrayProfile.Security.REALITY) add(XrayCapability.ANTI_DPI)
                add(XrayCapability.DNS_PROTECTION)
                add(XrayCapability.REALTIME_MEDIA)
            }

        private companion object {
            /**
             * Stable, user-meaningful label for the persisted typed profile. Kept
             * as a fixed internal name (NOT the relay displayName) so re-imports of
             * the same endpoint do not churn the durable profile's label; this is a
             * data-model name field, never rendered into the xray-core config.
             */
            const val IMPORTED_PROFILE_NAME = "Imported Xray profile"
        }
    }

/**
 * Persistence + environment seam for the Xray import ViewModel.
 *
 * Decouples the pure UI-state logic from the native-bearing settings/relay
 * stores, so the ViewModel's behaviour can be reasoned about (and tested)
 * without the NDK runtime.
 */
interface XrayProfilePersistence {
    /** Redacted, localized message shown when the import field is empty. */
    val emptyInputMessage: String

    /** Localized message shown when a valid config has no natively-supported outbounds. */
    val noSupportedNodesMessage: String

    /** Localized message shown when the input is neither an Xray config nor a share link. */
    val unparseableMessage: String

    /** Localized message shown when activating the translated relay profile fails. */
    val persistFailedMessage: String

    /** Localized message shown when durable provider/profile restore fails. */
    val restoreFailedMessage: String

    /** Localized message shown when the typed Xray parser rejects unsupported transport/security. */
    val unsupportedTypedProfileMessage: String

    /** Localized message shown when the typed Xray parser rejects a missing required field. */
    val missingTypedProfileFieldMessage: String

    /** Localized message shown when the typed Xray parser rejects an unsafe profile. */
    val unsafeTypedProfileMessage: String

    /**
     * Reads the current durable provider selection for cold-start/process-reentry
     * hydration. Native durable state returns the current native option from app
     * settings. Xray durable state returns the Xray option even when its active
     * profile is missing, with [XrayProviderSelection.acceptedProfile] null so the
     * UI stays fail-closed and asks the user to re-import.
     */
    suspend fun restoreSelection(): XrayProviderSelection

    /**
     * Persists the chosen provider [option].
     *
     * For the Xray provider ([XrayServiceModeOption.requiresXrayProfile]) the
     * validated [acceptedProfile] is persisted to the durable Keystore-split store
     * and the durable selection is flipped to Xray; the libXray runner then owns
     * the connection, so no native relay is activated. A null [acceptedProfile]
     * for an Xray option is fail-closed (the import cannot run via libXray).
     *
     * For the native options the durable provider selection is set to native and
     * the first supported profile from [profiles] (empty for native-direct) is
     * activated on the native relay. The stored Xray profile is preserved for an
     * explicit future switch back to the Xray provider.
     *
     * Suspends until the stores + settings are written so callers can sequence a
     * navigate-away only after a real, runnable connection is configured.
     */
    suspend fun persist(
        option: XrayServiceModeOption,
        profiles: List<ProxyProfile>,
        acceptedProfile: XrayProfile?,
    )
}
