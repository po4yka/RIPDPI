package com.poyka.ripdpi.ui.screens.awg

import androidx.lifecycle.ViewModel
import com.poyka.ripdpi.data.awg.AwgCohortCatalogData
import com.poyka.ripdpi.data.awg.AwgProfileForm
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * One row of the cohort-preset picker.
 *
 * [id] is the catalog preset id (or [AwgProfileForm.CUSTOM_COHORT_ID] for the free-form
 * sentinel). [displayNameKey] is a `strings.xml` resource key — the picker never carries
 * literal localized text — and is empty for the `Custom` sentinel, which the screen
 * labels from a fixed resource.
 */
data class AwgCohortOption(
    val id: String,
    val displayNameKey: String,
)

/**
 * UI state for the AmneziaWG profile editor.
 *
 * [editor] is the immutable field/validation snapshot. [cohortOptions] is the picker
 * content (every catalog preset plus the `Custom` sentinel). [privateKeyRevealed] /
 * [presharedKeyRevealed] gate the secret fields behind the biometric-reveal pattern used
 * by the other profile editors; both default to hidden.
 */
data class AmneziaWgProfileUiState(
    val editor: AmneziaWgEditorState,
    val cohortOptions: List<AwgCohortOption>,
    val privateKeyRevealed: Boolean = false,
    val presharedKeyRevealed: Boolean = false,
)

/**
 * Backing [ViewModel] for the AmneziaWG profile editor screen.
 *
 * The editor exposes every standard WireGuard field plus all 16 AmneziaWG obfuscation
 * fields inline (`Jc`, `Jmin`, `Jmax`, `S1`-`S4`, `H1`-`H4`, `I1`-`I5`) — they are not
 * hidden behind an "Advanced" toggle because they are server-coordinated and pasted
 * verbatim. Picking a cohort preset fills and locks the obfuscation group; the `Custom`
 * sentinel frees it again. Secret fields (private key, preshared key) stay hidden until a
 * biometric reveal is authorized.
 */
@HiltViewModel
class AmneziaWgProfileViewModel
    @Inject
    constructor(
        private val catalogProvider: AwgCohortCatalogProvider,
    ) : ViewModel() {
        private val catalog: AwgCohortCatalogData = catalogProvider.catalog()

        private val _uiState =
            MutableStateFlow(
                AmneziaWgProfileUiState(
                    editor = AmneziaWgEditorState.initial(),
                    cohortOptions = buildCohortOptions(catalog),
                ),
            )
        val uiState: StateFlow<AmneziaWgProfileUiState> = _uiState.asStateFlow()

        /**
         * Applies a user edit of [field] to [raw]. Edits to an obfuscation field while a
         * cohort preset is locked are ignored (the preset owns those values).
         */
        fun onFieldChanged(
            field: AwgEditorField,
            raw: String,
        ) {
            _uiState.update { it.copy(editor = it.editor.updateField(field, raw)) }
        }

        /**
         * Selects a cohort by [cohortId]. The [AwgProfileForm.CUSTOM_COHORT_ID] sentinel
         * frees the obfuscation fields; any other id fills and locks them from the catalog.
         */
        fun onCohortSelected(cohortId: String) {
            if (cohortId == AwgProfileForm.CUSTOM_COHORT_ID) {
                _uiState.update { it.copy(editor = it.editor.selectCustom()) }
                return
            }
            val preset = catalog.find(cohortId) ?: return
            _uiState.update { it.copy(editor = it.editor.selectCohort(preset)) }
        }

        /**
         * Replaces the editor state from a pasted AmneziaWG `.conf`. Malformed input or a
         * vanilla WireGuard config leaves the editor unchanged.
         */
        fun onConfPasted(conf: String) {
            _uiState.update { it.copy(editor = it.editor.populateFromConf(conf, catalog)) }
        }

        /** Reveals the private-key field after the biometric gate authorizes it. */
        fun onPrivateKeyRevealAuthorized() {
            _uiState.update { it.copy(privateKeyRevealed = true) }
        }

        /** Reveals the preshared-key field after the biometric gate authorizes it. */
        fun onPresharedKeyRevealAuthorized() {
            _uiState.update { it.copy(presharedKeyRevealed = true) }
        }

        /** Re-hides both secret fields (e.g. when the screen is left). */
        fun onSecretsRelocked() {
            _uiState.update { it.copy(privateKeyRevealed = false, presharedKeyRevealed = false) }
        }
    }

private fun buildCohortOptions(catalog: AwgCohortCatalogData): List<AwgCohortOption> =
    catalog.presets.map { AwgCohortOption(id = it.id, displayNameKey = it.displayNameKey) } +
        AwgCohortOption(id = AwgProfileForm.CUSTOM_COHORT_ID, displayNameKey = "")
