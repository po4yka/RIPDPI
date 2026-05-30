package com.poyka.ripdpi.ui.screens.hysteriav1

import androidx.lifecycle.ViewModel
import com.poyka.ripdpi.data.ProxyProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * UI state for the Hysteria v1 profile editor.
 *
 * [editor] is the immutable field/validation snapshot. [savedProfile] is set once
 * the user commits a complete, valid profile so the host can navigate away.
 */
data class HysteriaV1ProfileUiState(
    val editor: HysteriaV1ProfileEditorState,
    val savedProfile: ProxyProfile.HysteriaV1? = null,
)

/**
 * Backing [ViewModel] for the Hysteria v1 profile editor screen.
 *
 * Hysteria v1 is a **legacy** outbound (superseded by the active Hysteria2 kind):
 * the auth-type encoding is restricted to `string` / `base64`, the transport
 * protocol to `udp` / `wechat-video` / `faketcp`, the auth payload must be
 * non-blank, the up/down bandwidth must be strictly positive, and the port is
 * validated against `1..65535`. The SNI, ALPN, and obfuscation string are
 * optional.
 */
@HiltViewModel
class HysteriaV1ProfileViewModel
    @Inject
    constructor() : ViewModel() {
        private val _uiState =
            MutableStateFlow(HysteriaV1ProfileUiState(editor = HysteriaV1ProfileEditorState.initial()))
        val uiState: StateFlow<HysteriaV1ProfileUiState> = _uiState.asStateFlow()

        /** Applies a user edit of [field] to [raw], tracking the keystrokes regardless of validity. */
        fun onFieldChanged(
            field: HysteriaV1EditorField,
            raw: String,
        ) {
            _uiState.update { it.copy(editor = it.editor.updateField(field, raw)) }
        }

        /** Selects the auth-type encoding [value] from the whitelist. */
        fun onAuthTypeSelected(value: String) {
            _uiState.update { it.copy(editor = it.editor.selectAuthType(value)) }
        }

        /** Selects the protocol [value] from the whitelist. */
        fun onProtocolSelected(value: String) {
            _uiState.update { it.copy(editor = it.editor.selectProtocol(value)) }
        }

        /** Commits the editor to a [ProxyProfile.HysteriaV1]; a no-op when the required fields do not validate. */
        fun onSave() {
            val profile = _uiState.value.editor.toProfile() ?: return
            _uiState.update { it.copy(savedProfile = profile) }
        }
    }
