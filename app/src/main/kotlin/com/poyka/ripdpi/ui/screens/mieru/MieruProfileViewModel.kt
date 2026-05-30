package com.poyka.ripdpi.ui.screens.mieru

import androidx.lifecycle.ViewModel
import com.poyka.ripdpi.data.ProxyProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * UI state for the Mieru profile editor.
 *
 * [editor] is the immutable field/validation snapshot. [savedProfile] is set once
 * the user commits a complete, valid profile so the host can navigate away.
 */
data class MieruProfileUiState(
    val editor: MieruProfileEditorState,
    val savedProfile: ProxyProfile.Mieru? = null,
)

/**
 * Backing [ViewModel] for the Mieru profile editor screen.
 *
 * Mieru is an actively developed outbound (not legacy): the transport protocol is
 * restricted to `tcp` / `udp`, the multiplexing to `off` / `low` / `middle` /
 * `high`, and the MTU to `1280..1500`. The username and password must both be
 * non-blank and the port is validated against `1..65535`.
 */
@HiltViewModel
class MieruProfileViewModel
    @Inject
    constructor() : ViewModel() {
        private val _uiState = MutableStateFlow(MieruProfileUiState(editor = MieruProfileEditorState.initial()))
        val uiState: StateFlow<MieruProfileUiState> = _uiState.asStateFlow()

        /** Applies a user edit of [field] to [raw], tracking the keystrokes regardless of validity. */
        fun onFieldChanged(
            field: MieruEditorField,
            raw: String,
        ) {
            _uiState.update { it.copy(editor = it.editor.updateField(field, raw)) }
        }

        /** Selects the protocol [value] from the whitelist. */
        fun onProtocolSelected(value: String) {
            _uiState.update { it.copy(editor = it.editor.selectProtocol(value)) }
        }

        /** Selects the multiplexing [value] from the whitelist. */
        fun onMultiplexingSelected(value: String) {
            _uiState.update { it.copy(editor = it.editor.selectMultiplexing(value)) }
        }

        /** Commits the editor to a [ProxyProfile.Mieru]; a no-op when the required fields do not validate. */
        fun onSave() {
            val profile = _uiState.value.editor.toProfile() ?: return
            _uiState.update { it.copy(savedProfile = profile) }
        }
    }
