package com.poyka.ripdpi.ui.screens.xray

import androidx.lifecycle.ViewModel
import com.poyka.ripdpi.data.xray.XrayCapability
import com.poyka.ripdpi.data.xray.XrayImportParser
import com.poyka.ripdpi.data.xray.XrayProfile
import com.poyka.ripdpi.data.xray.XrayServiceModeOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * UI state for the Xray provider selection + profile import surface.
 *
 * @property selectedOption the currently chosen service-mode (provider) option.
 * @property rawInput the share link / config text the user is editing.
 * @property validating true while a parse/validate pass is in flight.
 * @property accepted the validated profile, ready to confirm (null until success).
 * @property capabilities jargon-free capability labels for the accepted profile.
 * @property errorMessage a redacted, user-safe validation error (null when none).
 * @property imported true once the accepted profile has been persisted.
 */
data class XrayImportUiState(
    val selectedOption: XrayServiceModeOption = XrayServiceModeOption.default,
    val rawInput: String = "",
    val validating: Boolean = false,
    val accepted: XrayProfile? = null,
    val acceptedConfigReady: Boolean = false,
    val capabilities: List<XrayCapability> = emptyList(),
    val errorMessage: String? = null,
    val imported: Boolean = false,
) {
    /** True when the chosen option needs a validated Xray profile to proceed. */
    val requiresXrayProfile: Boolean get() = selectedOption.requiresXrayProfile

    /** True when the user can finish: native option, or Xray option with an accepted config. */
    val canFinish: Boolean
        get() = !requiresXrayProfile || acceptedConfigReady
}

/**
 * Backs the Xray provider-selection + import screen and the onboarding
 * "validate the chosen mode" step.
 *
 * All parsing/validation is delegated to [XrayImportParser] (in
 * `:core:data:catalog`), which fails CLOSED and returns redacted error text.
 * This ViewModel only owns UI state transitions; it holds no secrets beyond the
 * in-memory typed profile, which the redactor guards before any logging.
 *
 * Persistence of the accepted provider selection + profile is delegated to the
 * injected [XrayProfilePersistence] seam so this ViewModel stays free of the
 * native runtime dependency.
 */
@HiltViewModel
class XrayProfileImportViewModel
    @Inject
    constructor(
        private val parser: XrayImportParser,
        private val persistence: XrayProfilePersistence,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(XrayImportUiState())
        val uiState: StateFlow<XrayImportUiState> = _uiState.asStateFlow()

        /** Selects a service-mode option, clearing any stale validation outcome. */
        fun selectOption(option: XrayServiceModeOption) {
            _uiState.update {
                it.copy(
                    selectedOption = option,
                    accepted = null,
                    acceptedConfigReady = false,
                    capabilities = emptyList(),
                    errorMessage = null,
                    imported = false,
                )
            }
        }

        /** Updates the editable import text, clearing the previous outcome. */
        fun onRawInputChange(value: String) {
            _uiState.update {
                it.copy(
                    rawInput = value,
                    accepted = null,
                    acceptedConfigReady = false,
                    capabilities = emptyList(),
                    errorMessage = null,
                )
            }
        }

        /**
         * Parses and validates the current [XrayImportUiState.rawInput]. On
         * success the accepted profile + capabilities are exposed; on failure a
         * redacted [XrayImportUiState.errorMessage] is set and no profile is
         * retained (fail-closed).
         */
        fun validate(upstreamTag: String = persistence.upstreamTag) {
            val input = _uiState.value.rawInput.trim()
            if (input.isEmpty()) {
                _uiState.update { it.copy(errorMessage = persistence.emptyInputMessage) }
                return
            }
            _uiState.update { it.copy(validating = true, errorMessage = null) }
            when (val result = parser.parse(input, upstreamTag)) {
                is XrayImportParser.Result.Accepted -> {
                    _uiState.update {
                        it.copy(
                            validating = false,
                            accepted = result.profile,
                            acceptedConfigReady = true,
                            capabilities = result.capabilities,
                            errorMessage = null,
                        )
                    }
                }

                is XrayImportParser.Result.Rejected -> {
                    _uiState.update {
                        it.copy(
                            validating = false,
                            accepted = null,
                            acceptedConfigReady = false,
                            capabilities = emptyList(),
                            errorMessage = result.message,
                        )
                    }
                }
            }
        }

        /**
         * Persists the chosen provider option and, when applicable, the accepted
         * Xray profile. No-op when the selection cannot finish yet.
         */
        fun confirm() {
            val state = _uiState.value
            if (!state.canFinish || state.imported) return
            persistence.persist(state.selectedOption, state.accepted)
            _uiState.update { it.copy(imported = true) }
        }
    }

/**
 * Persistence + environment seam for the Xray import ViewModel.
 *
 * Decouples the pure UI-state logic from the native-bearing settings/relay
 * stores, so the ViewModel's behaviour can be reasoned about (and, where the
 * toolchain allows, tested) without the NDK29 runtime.
 */
interface XrayProfilePersistence {
    /** The pinned xray-core upstream tag used for version-gated validation. */
    val upstreamTag: String

    /** Redacted, localized message shown when the import field is empty. */
    val emptyInputMessage: String

    /** Persists the chosen provider [option] and optional accepted [profile]. */
    fun persist(
        option: XrayServiceModeOption,
        profile: XrayProfile?,
    )
}
