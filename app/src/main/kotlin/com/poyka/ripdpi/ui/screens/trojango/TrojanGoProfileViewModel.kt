package com.poyka.ripdpi.ui.screens.trojango

import androidx.lifecycle.ViewModel
import com.poyka.ripdpi.data.ProxyProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * UI state for the Trojan-Go profile editor.
 *
 * [editor] is the immutable field/validation snapshot. [savedProfile] is set once
 * the user commits a complete, valid profile so the host can navigate away.
 */
data class TrojanGoProfileUiState(
    val editor: TrojanGoProfileEditorState,
    val savedProfile: ProxyProfile.TrojanGo? = null,
)

/**
 * Backing [ViewModel] for the Trojan-Go profile editor screen.
 *
 * Trojan-Go is a **legacy** outbound: the SMUX multiplexing mode is restricted to
 * `off` / `smux_v1`, and the optional inner cipher to the two Shadowsocks-AEAD
 * options (`aes-256-gcm` / `chacha20-ietf-poly1305`) plus the plain `none`. The
 * password must be non-blank and the port is validated against `1..65535`.
 */
@HiltViewModel
class TrojanGoProfileViewModel
    @Inject
    constructor() : ViewModel() {
        private val _uiState = MutableStateFlow(TrojanGoProfileUiState(editor = TrojanGoProfileEditorState.initial()))
        val uiState: StateFlow<TrojanGoProfileUiState> = _uiState.asStateFlow()

        /** Applies a user edit of [field] to [raw], tracking the keystrokes regardless of validity. */
        fun onFieldChanged(
            field: TrojanGoEditorField,
            raw: String,
        ) {
            _uiState.update { it.copy(editor = it.editor.updateField(field, raw)) }
        }

        /** Selects the mux [value] from the whitelist. */
        fun onMuxSelected(value: String) {
            _uiState.update { it.copy(editor = it.editor.selectMux(value)) }
        }

        /** Selects the inner cipher [value] from the whitelist. */
        fun onInnerCipherSelected(value: String) {
            _uiState.update { it.copy(editor = it.editor.selectInnerCipher(value)) }
        }

        /** Commits the editor to a [ProxyProfile.TrojanGo]; a no-op when the required fields do not validate. */
        fun onSave() {
            val profile = _uiState.value.editor.toProfile() ?: return
            _uiState.update { it.copy(savedProfile = profile) }
        }
    }
