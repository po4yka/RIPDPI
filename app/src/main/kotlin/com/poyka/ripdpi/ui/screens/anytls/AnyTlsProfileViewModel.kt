package com.poyka.ripdpi.ui.screens.anytls

import androidx.lifecycle.ViewModel
import com.poyka.ripdpi.data.ProxyProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * UI state for the AnyTLS profile editor.
 *
 * [editor] is the immutable field/validation snapshot. [savedProfile] is set once
 * the user commits a complete, valid profile so the host can navigate away.
 */
data class AnyTlsProfileUiState(
    val editor: AnyTlsProfileEditorState,
    val savedProfile: ProxyProfile.AnyTls? = null,
)

/**
 * Backing [ViewModel] for the AnyTLS profile editor screen.
 *
 * AnyTLS is a **current** outbound that runs over a single TLS session. It
 * authenticates with a password credential, the port is validated against
 * `1..65535`, and the SNI override is optional.
 */
@HiltViewModel
class AnyTlsProfileViewModel
    @Inject
    constructor() : ViewModel() {
        private val _uiState = MutableStateFlow(AnyTlsProfileUiState(editor = AnyTlsProfileEditorState.initial()))
        val uiState: StateFlow<AnyTlsProfileUiState> = _uiState.asStateFlow()

        /** Applies a user edit of [field] to [raw], tracking the keystrokes regardless of validity. */
        fun onFieldChanged(
            field: AnyTlsEditorField,
            raw: String,
        ) {
            _uiState.update { it.copy(editor = it.editor.updateField(field, raw)) }
        }

        /** Commits the editor to a [ProxyProfile.AnyTls]; a no-op when the required fields do not validate. */
        fun onSave() {
            val profile = _uiState.value.editor.toProfile() ?: return
            _uiState.update { it.copy(savedProfile = profile) }
        }
    }
