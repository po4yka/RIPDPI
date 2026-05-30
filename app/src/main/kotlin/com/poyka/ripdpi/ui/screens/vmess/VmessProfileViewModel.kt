package com.poyka.ripdpi.ui.screens.vmess

import androidx.lifecycle.ViewModel
import com.poyka.ripdpi.data.ProxyProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * UI state for the VMess profile editor.
 *
 * [editor] is the immutable field/validation snapshot. [savedProfile] is set once
 * the user commits a complete, valid profile so the host can navigate away.
 */
data class VmessProfileUiState(
    val editor: VmessProfileEditorState,
    val savedProfile: ProxyProfile.Vmess? = null,
)

/**
 * Backing [ViewModel] for the VMess profile editor screen.
 *
 * VMess is a **legacy** outbound: `alterId` is fixed at `0`, the cipher is
 * restricted to the two AEAD options (`aes-128-gcm` / `chacha20-poly1305`), and
 * the transport is one of `tcp` / `ws` / `h2` / `grpc`. The UUID is validated
 * against the RFC-4122 v4 shape and the port against `1..65535`.
 */
@HiltViewModel
class VmessProfileViewModel
    @Inject
    constructor() : ViewModel() {
        private val _uiState = MutableStateFlow(VmessProfileUiState(editor = VmessProfileEditorState.initial()))
        val uiState: StateFlow<VmessProfileUiState> = _uiState.asStateFlow()

        /** Applies a user edit of [field] to [raw], tracking the keystrokes regardless of validity. */
        fun onFieldChanged(
            field: VmessEditorField,
            raw: String,
        ) {
            _uiState.update { it.copy(editor = it.editor.updateField(field, raw)) }
        }

        /** Selects the cipher [value] from the whitelist. */
        fun onSecuritySelected(value: String) {
            _uiState.update { it.copy(editor = it.editor.selectSecurity(value)) }
        }

        /** Selects the transport [value] from the whitelist. */
        fun onTransportSelected(value: String) {
            _uiState.update { it.copy(editor = it.editor.selectTransport(value)) }
        }

        /** Commits the editor to a [ProxyProfile.Vmess]; a no-op when the required fields do not validate. */
        fun onSave() {
            val profile = _uiState.value.editor.toProfile() ?: return
            _uiState.update { it.copy(savedProfile = profile) }
        }
    }
