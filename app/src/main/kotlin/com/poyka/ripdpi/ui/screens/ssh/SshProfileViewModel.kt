package com.poyka.ripdpi.ui.screens.ssh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.proxyimport.RelayProfileActivator
import dagger.hilt.android.lifecycle.HiltViewModel
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
 * UI state for the SSH profile editor.
 *
 * [editor] is the immutable field/validation snapshot. [saving] is `true` while the
 * profile is being persisted and the native relay activated.
 */
data class SshProfileUiState(
    val editor: SshProfileEditorState,
    val saving: Boolean = false,
)

/**
 * Backing [ViewModel] for the SSH profile editor screen.
 *
 * SSH authenticates either with a password or a private key (the [authType]
 * selector decides which credential is required); the private-key passphrase and
 * the pinned `SHA256:` host-key fingerprint are optional, and a strict-host-key
 * toggle controls whether a host-key mismatch is rejected on connect.
 *
 * On save a complete editor is assembled into a
 * [com.poyka.ripdpi.data.ProxyProfile.Ssh] and applied as the active native relay
 * through [RelayProfileActivator] — the same activation path the import-confirmation
 * surface uses — so the editor leads directly to a working tunnel. The private key
 * and passphrase are persisted via the secure relay-credential store (Android
 * Keystore-backed) and stay hidden behind a biometric reveal.
 */
@HiltViewModel
class SshProfileViewModel
    @Inject
    constructor(
        private val relayActivator: RelayProfileActivator,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SshProfileUiState(editor = SshProfileEditorState.initial()))
        val uiState: StateFlow<SshProfileUiState> = _uiState.asStateFlow()
        private val savedEventChannel = Channel<Unit>(capacity = Channel.BUFFERED)
        val savedEvents: Flow<Unit> = savedEventChannel.receiveAsFlow()
        private var completed = false

        /** Applies a user edit of [field] to [raw], tracking the keystrokes regardless of validity. */
        fun onFieldChanged(
            field: SshEditorField,
            raw: String,
        ) {
            _uiState.update { it.copy(editor = it.editor.updateField(field, raw)) }
        }

        /** Selects the auth type [value] from the whitelist. */
        fun onAuthTypeSelected(value: String) {
            _uiState.update { it.copy(editor = it.editor.selectAuthType(value)) }
        }

        /** Toggles whether a host-key mismatch is rejected on connect (strict TOFU). */
        fun onStrictHostKeyChanged(value: Boolean) {
            _uiState.update { it.copy(editor = it.editor.setStrictHostKey(value)) }
        }

        /** Reveals the private-key field after the biometric gate authorizes it. */
        fun onPrivateKeyRevealAuthorized() {
            _uiState.update { it.copy(editor = it.editor.revealPrivateKey()) }
        }

        /** Reveals the passphrase field after the biometric gate authorizes it. */
        fun onPassphraseRevealAuthorized() {
            _uiState.update { it.copy(editor = it.editor.revealPassphrase()) }
        }

        /** Re-hides both secret fields (e.g. when the screen is left). */
        fun onSecretsRelocked() {
            _uiState.update { it.copy(editor = it.editor.relockSecrets()) }
        }

        /**
         * Assembles a complete editor into a [com.poyka.ripdpi.data.ProxyProfile.Ssh]
         * and applies it as the active native relay. A no-op when the required fields do
         * not validate or a save is already in flight.
         */
        fun onSave() {
            val profile = _uiState.value.editor.toProfile() ?: return
            if (_uiState.value.saving || completed) return
            _uiState.update { it.copy(saving = true) }
            viewModelScope.launch {
                try {
                    relayActivator.activate(profile)
                    completed = true
                    savedEventChannel.send(Unit)
                } finally {
                    // Reset the in-flight flag even if activation throws (e.g. a
                    // Keystore write failure) so the editor stays usable and the
                    // user can retry rather than being stranded on a disabled Save.
                    _uiState.update { it.copy(saving = false) }
                }
            }
        }
    }
