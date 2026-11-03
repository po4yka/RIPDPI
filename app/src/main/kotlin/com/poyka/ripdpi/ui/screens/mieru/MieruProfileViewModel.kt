package com.poyka.ripdpi.ui.screens.mieru

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.RelayKindMieru
import com.poyka.ripdpi.proxyimport.RelayProfileActivator
import com.poyka.ripdpi.ui.screens.connection.ConnectionDraft
import com.poyka.ripdpi.ui.screens.connection.ConnectionRelayArtifactRepository
import com.poyka.ripdpi.ui.screens.relayeditor.RelayEditorSaveFlow
import com.poyka.ripdpi.ui.screens.relayeditor.activateExplicitly
import com.poyka.ripdpi.ui.screens.relayeditor.loadRelayDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val loading: Boolean = false,
    val editing: Boolean = false,
    @StringRes val errorMessage: Int? = null,
    val notFound: Boolean = false,
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
        private val relayArtifacts: ConnectionRelayArtifactRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(MieruProfileUiState(editor = MieruProfileEditorState.initial()))
        val uiState: StateFlow<MieruProfileUiState> = _uiState.asStateFlow()
        private val saveFlow = RelayEditorSaveFlow(viewModelScope)
        val savedEvents: Flow<Unit> = saveFlow.savedEvents
        private var editingDraft: ConnectionDraft? = null
        private var requestedEditProfileId: String? = null

        fun loadProfile(profileId: String) {
            if (profileId.isBlank()) {
                // An edit route without a profile id has nothing to edit, so it must not show the new-profile form.
                _uiState.update { it.copy(notFound = true) }
                return
            }
            if (_uiState.value.loading || editingDraft != null) return
            requestedEditProfileId = profileId
            _uiState.update { it.copy(loading = true, errorMessage = null) }
            viewModelScope.launch {
                val draft =
                    relayArtifacts.loadRelayDraft(profileId, RelayKindMieru) { notFound, error ->
                        _uiState.update { it.copy(loading = false, notFound = notFound, errorMessage = error) }
                    } ?: return@launch
                editingDraft = draft
                _uiState.update {
                    it.copy(editor = MieruProfileEditorState.fromDraft(draft), loading = false, editing = true)
                }
            }
        }

        /** Applies a user edit of [field] to [raw], tracking the keystrokes regardless of validity. */
        fun onFieldChanged(
            field: MieruEditorField,
            raw: String,
        ) {
            if (_uiState.value.editing && field == MieruEditorField.DISPLAY_NAME) return
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
         * and saves it as a new or existing relay profile. A no-op when the required fields do
         * not validate or a save is already in flight; surfaces [errorMessage] and keeps
         * the user on the screen when activation does not take, so the editor never reports
         * success for a relay it failed to create.
         */
        fun onSave() {
            val profile = _uiState.value.editor.toProfile() ?: return
            val editTargetReady = requestedEditProfileId == null || editingDraft != null
            val busy = _uiState.value.saving || _uiState.value.loading
            if (busy || saveFlow.completed || !editTargetReady) return
            _uiState.update { it.copy(saving = true, errorMessage = null) }
            saveFlow.save(
                block = {
                    val original = editingDraft
                    if (original == null) {
                        relayActivator.activateExplicitly(profile)
                    } else {
                        relayArtifacts.persist(
                            original.copy(
                                relayServer = profile.server,
                                relayServerPort = profile.serverPort.toString(),
                                relayMieruUsername = profile.username,
                                relayMieruPassword = profile.password,
                                relayMieruProtocol = profile.protocol,
                                relayMieruMultiplexing = profile.multiplexing,
                                relayMieruMtu = profile.mtu.toString(),
                            ),
                        )
                        true
                    }
                },
            ) { activated ->
                val error = R.string.relay_editor_activation_failed.takeUnless { activated }
                _uiState.update { it.copy(saving = false, errorMessage = error) }
            }
        }
    }
