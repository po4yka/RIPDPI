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
    @StringRes val admissionErrorRes: Int? = null,
    @StringRes val errorRes: Int? = null,
)

/** Confirms a share-link import and saves the profile for later selection. */
@HiltViewModel
class ProfileImportConfirmViewModel
    @Inject
    constructor(
        private val relayActivator: RelayProfileActivator,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ProfileImportConfirmUiState())
        val uiState: StateFlow<ProfileImportConfirmUiState> = _uiState.asStateFlow()
        private val importedEventChannel = Channel<Unit>(capacity = Channel.BUFFERED)
        val importedEvents: Flow<Unit> = importedEventChannel.receiveAsFlow()
        private var completed = false
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
            completed = false
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
            if (state.admissionErrorRes != null || state.importing || completed) return
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
                completed = saved
                _uiState.update {
                    it.copy(importing = false, errorRes = R.string.import_profile_confirm_error.takeUnless { saved })
                }
                if (saved) importedEventChannel.send(Unit)
            }
        }
    }
