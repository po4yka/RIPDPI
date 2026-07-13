package com.poyka.ripdpi.ui.screens.mieru

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.proxyimport.RelayProfileActivator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Mieru profile editor.
 *
 * [editor] is the immutable field/validation snapshot. [saving] is `true` while the
 * profile is being persisted and the native relay activated. [errorMessage]
 * carries a string resource when activation does not take, so the editor surfaces the
 * failure and keeps the user on the screen instead of silently discarding the input.
 */
data class MieruProfileUiState(
    val editor: MieruProfileEditorState,
    val saving: Boolean = false,
    @StringRes val errorMessage: Int? = null,
)

/**
 * Backing [ViewModel] for the Mieru profile editor screen.
 *
 * Mieru is an actively developed outbound (not legacy): the transport protocol is
 * restricted to `tcp` / `udp`, the multiplexing to `off` / `low` / `middle` /
 * `high`, and the MTU to `1280..1500`. The username and password must both be
 * non-blank and the port is validated against `1..65535`.
 *
 * On save a complete editor is assembled into a
 * [com.poyka.ripdpi.data.ProxyProfile.Mieru] and applied as the active native relay
 * through [RelayProfileActivator] — the same activation path the import-confirmation
 * surface uses — so the editor leads directly to a working tunnel. The username and
 * password are persisted via the secure relay-credential store (Android Keystore-backed).
 */
@HiltViewModel
class MieruProfileViewModel
    @Inject
    constructor(
        private val relayActivator: RelayProfileActivator,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(MieruProfileUiState(editor = MieruProfileEditorState.initial()))
        val uiState: StateFlow<MieruProfileUiState> = _uiState.asStateFlow()
        private val savedEventChannel = Channel<Unit>(capacity = Channel.BUFFERED)
        val savedEvents: Flow<Unit> = savedEventChannel.receiveAsFlow()
        private var completed = false

        /** Applies a user edit of [field] to [raw], tracking the keystrokes regardless of validity. */
        fun onFieldChanged(
            field: MieruEditorField,
            raw: String,
        ) {
            _uiState.update { it.copy(editor = it.editor.updateField(field, raw), errorMessage = null) }
        }

        /** Selects the protocol [value] from the whitelist. */
        fun onProtocolSelected(value: String) {
            _uiState.update { it.copy(editor = it.editor.selectProtocol(value)) }
        }

        /** Selects the multiplexing [value] from the whitelist. */
        fun onMultiplexingSelected(value: String) {
            _uiState.update { it.copy(editor = it.editor.selectMultiplexing(value)) }
        }

        /**
         * Assembles a complete editor into a [com.poyka.ripdpi.data.ProxyProfile.Mieru]
         * and applies it as the active native relay. A no-op when the required fields do
         * not validate or a save is already in flight; surfaces [errorMessage] and keeps
         * the user on the screen when activation does not take, so the editor never reports
         * success for a relay it failed to create.
         */
        fun onSave() {
            val profile = _uiState.value.editor.toProfile() ?: return
            if (_uiState.value.saving || completed) return
            _uiState.update { it.copy(saving = true, errorMessage = null) }
            viewModelScope.launch {
                val activated =
                    runCatching { relayActivator.activate(profile) }
                        .getOrElse { error ->
                            if (error is CancellationException) throw error
                            false
                        }
                _uiState.update {
                    if (activated) {
                        it.copy(saving = false)
                    } else {
                        it.copy(saving = false, errorMessage = R.string.relay_editor_activation_failed)
                    }
                }
                if (activated) {
                    completed = true
                    savedEventChannel.send(Unit)
                }
            }
        }
    }
