package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.nativeVlessProfileRejection
import com.poyka.ripdpi.data.validateNativeRelayProfileResult
import com.poyka.ripdpi.proxyimport.PendingProxyImportStore
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

/** UI state for the single-profile import-confirmation screen. */
data class ProfileImportConfirmUiState(
    val profile: ProxyProfile? = null,
    val importing: Boolean = false,
    /** Set once Add saved the profile; the screen then offers Select and Connect. */
    val saved: Boolean = false,
    val selecting: Boolean = false,
    @StringRes val admissionErrorRes: Int? = null,
    @StringRes val errorRes: Int? = null,
)

/** Confirms a share-link import, saves the profile, and can then make it the active server. */
@HiltViewModel
class ProfileImportConfirmViewModel
    @Inject
    constructor(
        private val relayActivator: RelayProfileActivator,
        private val serverSelection: ImportedServerSelection,
    ) : ViewModel() {
        internal constructor(relayActivator: RelayProfileActivator) : this(
            relayActivator,
            { ImportedServerSelectionResult.Failed },
        )

        private val _uiState = MutableStateFlow(ProfileImportConfirmUiState())
        val uiState: StateFlow<ProfileImportConfirmUiState> = _uiState.asStateFlow()
        private val selectedEventChannel = Channel<Boolean>(capacity = Channel.BUFFERED)

        /** Emits once the saved profile is the active server: `true` when the caller must also start the connection. */
        val selectedEvents: Flow<Boolean> = selectedEventChannel.receiveAsFlow()
        private var loadedImportToken: String? = null

        /** Claims the pending profile once so credentials never enter navigation saved state. */
        internal fun loadProfile(
            importToken: String,
            pendingImports: PendingProxyImportStore = PendingProxyImportStore.process,
        ): Boolean {
            if (loadedImportToken == importToken) return _uiState.value.profile != null
            loadedImportToken = importToken
            setProfile(pendingImports.claim(importToken))
            return _uiState.value.profile != null
        }

        /** Seeds the screen with the [profile] parsed from the inbound share link. */
        internal fun setProfile(profile: ProxyProfile?) {
            _uiState.value =
                ProfileImportConfirmUiState(
                    profile = profile,
                    admissionErrorRes = profile?.let(::admissionError),
                )
        }

        @StringRes
        private fun admissionError(profile: ProxyProfile): Int? =
            if (nativeVlessProfileRejection(profile) != null) {
                R.string.import_error_unsupported
            } else {
                validateNativeRelayProfileResult(profile).fold(
                    onSuccess = { supported -> R.string.import_error_unsupported.takeUnless { supported } },
                    onFailure = { R.string.import_profile_confirm_error },
                )
            }

        fun confirm() {
            val state = _uiState.value
            val profile = state.profile ?: return
            if (state.admissionErrorRes != null || state.importing || state.saved) return
            _uiState.update { it.copy(importing = true, errorRes = null) }
            viewModelScope.launch {
                val saved =
                    try {
                        relayActivator.saveImport(profile)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                _uiState.update {
                    it.copy(
                        importing = false,
                        saved = saved,
                        errorRes = R.string.import_profile_confirm_error.takeUnless { saved },
                    )
                }
            }
        }

        /** Makes the saved profile the active server; [connect] asks the caller to start the connection too. */
        fun select(connect: Boolean) {
            val state = _uiState.value
            val profile = state.profile ?: return
            if (!state.saved || state.selecting) return
            _uiState.update { it.copy(selecting = true, errorRes = null) }
            viewModelScope.launch {
                val result =
                    try {
                        // The saved relay profile keeps the parsed profile's id.
                        serverSelection.select(profile.id)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        ImportedServerSelectionResult.Failed
                    }
                if (result == ImportedServerSelectionResult.Failed) {
                    _uiState.update { it.copy(selecting = false, errorRes = R.string.relay_editor_activation_failed) }
                } else {
                    // `selecting` stays set: the screen is leaving, and a second tap must not select again.
                    selectedEventChannel.send(connect && result == ImportedServerSelectionResult.Selected)
                }
            }
        }
    }
