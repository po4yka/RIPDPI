package com.poyka.ripdpi.ui.screens.ssh

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * UI state for the SSH profile editor.
 *
 * [editor] is the immutable field/validation snapshot. [saved] flips to `true`
 * once the user commits a complete, valid profile so the host can navigate away.
 * SSH is editor-only (no subscription / URI import path) so the editor produces
 * no `ProxyProfile`; persistence is wired by the host once the engine lands.
 */
data class SshProfileUiState(
    val editor: SshProfileEditorState,
    val saved: Boolean = false,
)

/**
 * Backing [ViewModel] for the SSH profile editor screen.
 *
 * SSH authenticates either with a password or a private key (the [authType]
 * selector decides which credential is required); the private-key passphrase and
 * the pinned `SHA256:` host-key fingerprint are optional, and a strict-host-key
 * toggle controls whether a host-key mismatch is rejected on connect. The private
 * key and passphrase are persisted via the secure relay-credential store (Android
 * Keystore-backed `EncryptedFile`-equivalent), the same mechanism the WireGuard /
 * AmneziaWG editor uses, and stay hidden behind a biometric reveal.
 */
@HiltViewModel
class SshProfileViewModel
    @Inject
    constructor() : ViewModel() {
        private val _uiState = MutableStateFlow(SshProfileUiState(editor = SshProfileEditorState.initial()))
        val uiState: StateFlow<SshProfileUiState> = _uiState.asStateFlow()

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

        /** Commits the editor; a no-op when the required fields do not validate. */
        fun onSave() {
            if (!_uiState.value.editor.isComplete) return
            _uiState.update { it.copy(saved = true) }
        }
    }
