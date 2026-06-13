package com.poyka.ripdpi.ui.screens.xray

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.subscription.XrayConfigImportParser
import com.poyka.ripdpi.data.subscription.XrayConfigImportResult
import com.poyka.ripdpi.data.subscription.XraySkipReason
import com.poyka.ripdpi.data.subscription.XraySkippedNode
import com.poyka.ripdpi.data.xray.XrayCapability
import com.poyka.ripdpi.data.xray.XrayServiceModeOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * @property imported true once the translated profile(s) have been persisted.
 */
data class XrayImportUiState(
    val selectedOption: XrayServiceModeOption = XrayServiceModeOption.default,
    val rawInput: String = "",
    val validating: Boolean = false,
    val acceptedConfigReady: Boolean = false,
    val importableCount: Int = 0,
    val capabilities: List<XrayCapability> = emptyList(),
    val skipped: List<XraySkippedNode> = emptyList(),
    val errorMessage: String? = null,
    val imported: Boolean = false,
) {
    /** True when the chosen option needs a validated Xray profile to proceed. */
    val requiresXrayProfile: Boolean get() = selectedOption.requiresXrayProfile

    /** True when the user can finish: native option, or Xray option with an importable config. */
    val canFinish: Boolean
        get() = !requiresXrayProfile || acceptedConfigReady
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

        /** Profiles translated by the last successful [validate]; not exposed to the UI. */
        private var translatedProfiles: List<ProxyProfile> = emptyList()

        /** Guards [confirm] against re-entry while the async persist is in flight. */
        private var importInFlight: Boolean = false

        /** Selects a service-mode option, clearing any stale validation outcome. */
        fun selectOption(option: XrayServiceModeOption) {
            translatedProfiles = emptyList()
            _uiState.update {
                it.copy(
                    selectedOption = option,
                    acceptedConfigReady = false,
                    importableCount = 0,
                    capabilities = emptyList(),
                    skipped = emptyList(),
                    errorMessage = null,
                    imported = false,
                )
            }
        }

        /** Updates the editable import text, clearing the previous outcome. */
        fun onRawInputChange(value: String) {
            translatedProfiles = emptyList()
            _uiState.update {
                it.copy(
                    rawInput = value,
                    acceptedConfigReady = false,
                    importableCount = 0,
                    capabilities = emptyList(),
                    skipped = emptyList(),
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
            val input = _uiState.value.rawInput.trim()
            if (input.isEmpty()) {
                _uiState.update { it.copy(errorMessage = persistence.emptyInputMessage) }
                return
            }
            _uiState.update { it.copy(validating = true, errorMessage = null) }
            val groupId = UUID.randomUUID().toString()
            when (val result = XrayConfigImportParser.parse(input, groupId)) {
                is XrayConfigImportResult.Translated -> {
                    applyTranslation(result)
                }

                is XrayConfigImportResult.Unparseable -> {
                    translatedProfiles = emptyList()
                    _uiState.update {
                        it.copy(
                            validating = false,
                            acceptedConfigReady = false,
                            importableCount = 0,
                            capabilities = emptyList(),
                            skipped = emptyList(),
                            errorMessage = persistence.unparseableMessage,
                        )
                    }
                }
            }
        }

        private fun applyTranslation(result: XrayConfigImportResult.Translated) {
            translatedProfiles = result.profiles
            if (result.profiles.isEmpty()) {
                // Valid config but every node was skipped — surface the reasons.
                _uiState.update {
                    it.copy(
                        validating = false,
                        acceptedConfigReady = false,
                        importableCount = 0,
                        capabilities = emptyList(),
                        skipped = result.skipped,
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
            _uiState.update {
                it.copy(
                    validating = false,
                    acceptedConfigReady = true,
                    importableCount = 1,
                    capabilities = capabilitiesFor(listOf(activated)),
                    skipped = result.skipped + deferred,
                    errorMessage = null,
                )
            }
        }

        /**
         * Hands the translated profiles to the persistence seam (which activates the
         * first supported one on the native relay) and records the chosen mode. Awaits
         * the persist so [XrayImportUiState.imported] (the screen's navigate-away
         * signal) only flips once the relay is actually written. No-op when the
         * selection cannot finish yet or a persist is already in flight.
         */
        fun confirm() {
            val state = _uiState.value
            if (!state.canFinish || state.imported || importInFlight) return
            importInFlight = true
            viewModelScope.launch {
                val result = runCatching { persistence.persist(state.selectedOption, translatedProfiles) }
                importInFlight = false
                result.fold(
                    onSuccess = { _uiState.update { it.copy(imported = true) } },
                    onFailure = { error ->
                        // Don't swallow structured-concurrency cancellation.
                        if (error is CancellationException) throw error
                        // Activation failed (store/settings I/O): surface a retryable error
                        // instead of crashing the scope or wedging on a dead Finish button.
                        _uiState.update { it.copy(errorMessage = persistence.persistFailedMessage) }
                    },
                )
            }
        }

        private fun capabilitiesFor(profiles: List<ProxyProfile>): List<XrayCapability> =
            buildList {
                add(XrayCapability.VPN_PRIVACY)
                add(XrayCapability.RELAY)
                if (profiles.any { it is ProxyProfile.VlessReality }) add(XrayCapability.ANTI_DPI)
                add(XrayCapability.DNS_PROTECTION)
                add(XrayCapability.REALTIME_MEDIA)
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

    /**
     * Persists the chosen provider [option] and activates the first supported
     * profile from [profiles] (empty for native options) on the native relay.
     * Suspends until the relay + settings are written so callers can sequence a
     * navigate-away only after a real, runnable connection is configured.
     */
    suspend fun persist(
        option: XrayServiceModeOption,
        profiles: List<ProxyProfile>,
    )
}
