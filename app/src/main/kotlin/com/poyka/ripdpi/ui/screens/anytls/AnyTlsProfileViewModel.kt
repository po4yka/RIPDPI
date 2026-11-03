package com.poyka.ripdpi.ui.screens.anytls

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.proxyimport.RelayProfileActivator
import com.poyka.ripdpi.ui.screens.relayeditor.RelayEditorSaveFlow
import com.poyka.ripdpi.ui.screens.relayeditor.activateExplicitly
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * UI state for the AnyTLS profile editor.
 *
 * [editor] is the immutable field/validation snapshot. [saving] is `true` while the
 * profile is being persisted and the native relay activated. [errorMessage]
 * carries a string resource when activation does not take, so the editor surfaces the
 * failure and keeps the user on the screen instead of silently discarding the input.
 */
data class AnyTlsProfileUiState(
    val editor: AnyTlsProfileEditorState,
    val saving: Boolean = false,
    @StringRes val errorMessage: Int? = null,
)

/**
 * Backing [ViewModel] for the AnyTLS profile editor screen.
 *
 * AnyTLS is a **current** outbound that runs over a single TLS session. It
 * authenticates with a password credential, the port is validated against
 * `1..65535`, and the SNI override is optional.
 *
 * On save a complete editor is assembled into a
 * [com.poyka.ripdpi.data.ProxyProfile.AnyTls] and applied as the active native relay
 * through [RelayProfileActivator] — the same activation path the import-confirmation
 * surface uses — so the editor leads directly to a working tunnel. The password is
 * persisted via the secure relay-credential store (Android Keystore-backed).
 */
@HiltViewModel
class AnyTlsProfileViewModel
    @Inject
    constructor(
        private val relayActivator: RelayProfileActivator,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(AnyTlsProfileUiState(editor = AnyTlsProfileEditorState.initial()))
        val uiState: StateFlow<AnyTlsProfileUiState> = _uiState.asStateFlow()
        private val saveFlow = RelayEditorSaveFlow(viewModelScope)
        val savedEvents: Flow<Unit> = saveFlow.savedEvents

        /** Applies a user edit of [field] to [raw], tracking the keystrokes regardless of validity. */
        fun onFieldChanged(
            field: AnyTlsEditorField,
            raw: String,
        ) {
            _uiState.update { it.copy(editor = it.editor.updateField(field, raw), errorMessage = null) }
        }

        /**
         * Assembles a complete editor into a [com.poyka.ripdpi.data.ProxyProfile.AnyTls]
         * and applies it as the active native relay. A no-op when the required fields do
         * not validate or a save is already in flight; surfaces [errorMessage] and keeps
         * the user on the screen when activation does not take, so the editor never reports
         * success for a relay it failed to create.
         */
        fun onSave() {
            val profile = _uiState.value.editor.toProfile() ?: return
            if (_uiState.value.saving || saveFlow.completed) return
            _uiState.update { it.copy(saving = true, errorMessage = null) }
            saveFlow.save({ relayActivator.activateExplicitly(profile) }) { activated ->
                val error = R.string.relay_editor_activation_failed.takeUnless { activated }
                _uiState.update { it.copy(saving = false, errorMessage = error) }
            }
        }
    }
