package com.poyka.ripdpi.ui.screens.xray

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.subscription.XrayConfigImportParser
import com.poyka.ripdpi.data.subscription.XrayConfigImportResult
import com.poyka.ripdpi.data.subscription.XraySkipReason
import com.poyka.ripdpi.data.subscription.XraySkippedNode
import com.poyka.ripdpi.data.xray.XrayCapability
import com.poyka.ripdpi.data.xray.XrayImportParser
import com.poyka.ripdpi.data.xray.XrayProfile
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

        /**
         * The typed Xray profile the libXray provider runs, produced by the
         * validated [XrayImportParser] (render + [com.poyka.ripdpi.data.XrayConfigValidator]
         * gate) — NOT hand-converted from the translated relay profile. Null when
         * the chosen option is native, or when the validated parse produced no
         * typed profile (raw-JSON path) / was rejected, in which case the Xray
         * option fails closed at validate-time. Never exposed to the UI and never
         * logged.
         */
        private var acceptedXrayProfile: XrayProfile? = null

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

        /** Selects a service-mode option, clearing any stale validation outcome. */
        fun selectOption(option: XrayServiceModeOption) {
            translatedProfiles = emptyList()
            acceptedXrayProfile = null
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
            acceptedXrayProfile = null
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
                    acceptedXrayProfile = null
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
            acceptedXrayProfile = null
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
            if (_uiState.value.requiresXrayProfile) {
                // The Xray (libXray) option must run through the SAME validated
                // render + validator gate the runner reads, so derive the typed
                // profile from XrayImportParser — never from the translated relay
                // profile (lossy and validation-bypassing). Fail closed at
                // validate-time when no validated typed profile is available, so a
                // non-REALITY / invalid config never enables Finish.
                applyXrayValidation(result.skipped + deferred)
                return
            }
            // Native options need no typed Xray profile.
            acceptedXrayProfile = null
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
         * Runs the validated [XrayImportParser] over the current raw input and,
         * only on an [XrayImportParser.Result.Accepted] carrying a typed profile,
         * marks the Xray option ready to finish. A rejection or a typed-less accept
         * (the raw-JSON path the libXray runner cannot source) fails closed: no
         * typed profile is retained and Finish stays disabled, with the parser's
         * already-redacted rejection reason surfaced when present.
         */
        private fun applyXrayValidation(skipped: List<XraySkippedNode>) {
            val input = _uiState.value.rawInput.trim()
            val parsed =
                xrayImportParser.parse(
                    input,
                    upstreamTag = XRAY_UPSTREAM_TAG,
                    profileName = IMPORTED_PROFILE_NAME,
                )
            when (parsed) {
                is XrayImportParser.Result.Accepted -> {
                    val typed = parsed.profile
                    if (typed == null) {
                        failXrayValidation(skipped, persistence.noSupportedNodesMessage)
                    } else {
                        acceptedXrayProfile = typed
                        _uiState.update {
                            it.copy(
                                validating = false,
                                acceptedConfigReady = true,
                                importableCount = 1,
                                capabilities = parsed.capabilities,
                                skipped = skipped,
                                errorMessage = null,
                            )
                        }
                    }
                }

                is XrayImportParser.Result.Rejected -> {
                    // parsed.message is already redacted by XrayProfileRedactor.
                    failXrayValidation(skipped, parsed.message)
                }
            }
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
                    capabilities = emptyList(),
                    skipped = skipped,
                    errorMessage = message,
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
                val result =
                    runCatching {
                        persistence.persist(state.selectedOption, translatedProfiles, acceptedXrayProfile)
                    }
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

        private companion object {
            /**
             * Stable, user-meaningful label for the persisted typed profile. Kept
             * as a fixed internal name (NOT the relay displayName) so re-imports of
             * the same endpoint do not churn the durable profile's label; this is a
             * data-model name field, never rendered into the xray-core config.
             */
            const val IMPORTED_PROFILE_NAME = "Imported Xray profile"

            /**
             * xray-core release tag the validator gates version-dependent rules
             * against. Mirrors the production render call in
             * `core/service` `XrayProviderRouteBuilder` (`upstreamTag = "proxy"`),
             * which carries no version tuple, so REALITY+XHTTP is not version-gated.
             */
            const val XRAY_UPSTREAM_TAG = "proxy"
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
     * Persists the chosen provider [option].
     *
     * For the Xray provider ([XrayServiceModeOption.requiresXrayProfile]) the
     * validated [acceptedProfile] is persisted to the durable Keystore-split store
     * and the durable selection is flipped to Xray; the libXray runner then owns
     * the connection, so no native relay is activated. A null [acceptedProfile]
     * for an Xray option is fail-closed (the import cannot run via libXray).
     *
     * For the native options the durable Xray selection is cleared and the first
     * supported profile from [profiles] (empty for native-direct) is activated on
     * the native relay — pre-existing behaviour, unchanged.
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
