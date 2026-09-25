package com.poyka.ripdpi.ui.screens.ssh

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.ConfigDraft
import com.poyka.ripdpi.activities.ConfigRelayArtifactRepository
import com.poyka.ripdpi.data.RelayKindSsh
import com.poyka.ripdpi.data.RelaySshAuthTypePassword
import com.poyka.ripdpi.data.RelaySshAuthTypePrivateKey
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
 * UI state for the SSH profile editor.
 *
 * [editor] is the immutable field/validation snapshot. [saving] is `true` while the
 * profile is being persisted and the native relay activated.
 */
data class SshProfileUiState(
    val editor: SshProfileEditorState,
    val saving: Boolean = false,
    val loading: Boolean = false,
    val editing: Boolean = false,
    @StringRes val errorMessage: Int? = null,
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
 * [com.poyka.ripdpi.data.ProxyProfile.Ssh]. New profiles use [RelayProfileActivator];
 * saved profiles keep their identity and use the guarded profile mutation path. The private key
 * and passphrase are persisted via the secure relay-credential store (Android
 * Keystore-backed) and stay hidden behind a biometric reveal.
 */
@HiltViewModel
class SshProfileViewModel
    @Inject
    constructor(
        private val relayActivator: RelayProfileActivator,
        private val relayArtifacts: ConfigRelayArtifactRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SshProfileUiState(editor = SshProfileEditorState.initial()))
        val uiState: StateFlow<SshProfileUiState> = _uiState.asStateFlow()
        private val savedEventChannel = Channel<Unit>(capacity = Channel.BUFFERED)
        val savedEvents: Flow<Unit> = savedEventChannel.receiveAsFlow()
        private var completed = false
        private var editingDraft: ConfigDraft? = null
        private var requestedEditProfileId: String? = null

        fun loadProfile(profileId: String) {
            if (profileId.isBlank() || _uiState.value.loading || editingDraft != null) return
            requestedEditProfileId = profileId
            _uiState.update { it.copy(loading = true, errorMessage = null) }
            viewModelScope.launch {
                val draft =
                    runCatching { relayArtifacts.hydrateProfile(profileId) }
                        .getOrElse { error ->
                            if (error is CancellationException) throw error
                            _uiState.update {
                                it.copy(
                                    loading = false,
                                    errorMessage = R.string.relay_editor_activation_failed,
                                )
                            }
                            return@launch
                        }
                if (draft.relayKind != RelayKindSsh) {
                    _uiState.update { it.copy(loading = false, errorMessage = R.string.relay_editor_activation_failed) }
                    return@launch
                }
                editingDraft = draft
                _uiState.update {
                    it.copy(
                        editor = SshProfileEditorState.fromDraft(draft),
                        loading = false,
                        editing = true,
                    )
                }
            }
        }

        /** Applies a user edit of [field] to [raw], tracking the keystrokes regardless of validity. */
        fun onFieldChanged(
            field: SshEditorField,
            raw: String,
        ) {
            if (_uiState.value.editing && field == SshEditorField.DISPLAY_NAME) return
            _uiState.update { it.copy(editor = it.editor.updateField(field, raw), errorMessage = null) }
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
         * and saves it as a new or existing relay profile. A no-op when the required fields do
         * not validate or a save is already in flight.
         */
        fun onSave() {
            val editor = _uiState.value.editor
            val profile = editor.toProfile() ?: return
            if (_uiState.value.saving || _uiState.value.loading || completed ||
                (requestedEditProfileId != null && editingDraft == null)
            ) {
                return
            }
            _uiState.update { it.copy(saving = true) }
            viewModelScope.launch {
                val activated =
                    runCatching {
                        val original = editingDraft
                        if (original == null) {
                            relayActivator.activate(profile)
                        } else {
                            val authChanged = profile.authType != original.relaySshAuthType
                            relayArtifacts.persist(
                                original.copy(
                                    relayServer = profile.server,
                                    relayServerPort = profile.serverPort.toString(),
                                    relaySshUsername = profile.username,
                                    relaySshAuthType = profile.authType,
                                    relaySshPassword =
                                        if (authChanged &&
                                            profile.authType == RelaySshAuthTypePrivateKey
                                        ) {
                                            ""
                                        } else {
                                            editor.rawText(SshEditorField.PASSWORD)
                                        },
                                    relaySshPrivateKey =
                                        if (authChanged &&
                                            profile.authType == RelaySshAuthTypePassword
                                        ) {
                                            ""
                                        } else {
                                            editor.rawText(SshEditorField.PRIVATE_KEY)
                                        },
                                    relaySshPrivateKeyPassphrase =
                                        if (authChanged &&
                                            profile.authType == RelaySshAuthTypePassword
                                        ) {
                                            ""
                                        } else {
                                            editor.rawText(SshEditorField.PRIVATE_KEY_PASSPHRASE)
                                        },
                                    relaySshHostKeyFingerprint = profile.hostKeyFingerprint.orEmpty(),
                                    relaySshStrictHostKey = profile.strictHostKey,
                                ),
                            )
                            true
                        }
                    }.getOrElse { error ->
                        if (error is CancellationException) throw error
                        false
                    }
                _uiState.update {
                    it.copy(
                        saving = false,
                        errorMessage = if (activated) null else R.string.relay_editor_activation_failed,
                    )
                }
                if (activated) {
                    completed = true
                    savedEventChannel.send(Unit)
                }
            }
        }
    }
