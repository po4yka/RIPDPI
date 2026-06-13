package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayProfileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI state for the single-profile import-confirmation screen. */
data class ProfileImportConfirmUiState(
    val profile: ProxyProfile? = null,
    val importing: Boolean = false,
    val imported: Boolean = false,
)

/**
 * Backing [ViewModel] for the single-profile import-confirmation destination.
 *
 * This is the import-confirmation surface, not a full editor: it shows the parsed
 * [ProxyProfile] and an "Add" action that persists it into a [ProxyGroupType.BASIC]
 * group and activates it as the native relay via [NativeRelayProfileActivator]. The
 * handler activity navigates here after parsing a `vless://` / `ss://` / `trojan://`
 * style share link.
 *
 * The store/settings dependencies are kept on the constructor (rather than injecting
 * [NativeRelayProfileActivator] directly) so the existing unit tests can drive the
 * activation with their fakes; the activator is the single source of the
 * profile→relay mapping shared with the Xray import flow.
 */
@HiltViewModel
class ProfileImportConfirmViewModel
    @Inject
    constructor(
        repository: ProxyGroupRepository,
        relayProfileStore: RelayProfileStore,
        relayCredentialStore: RelayCredentialStore,
        settingsRepository: AppSettingsRepository,
    ) : ViewModel() {
        private val activator =
            NativeRelayProfileActivator(
                repository = repository,
                relayProfileStore = relayProfileStore,
                relayCredentialStore = relayCredentialStore,
                settingsRepository = settingsRepository,
            )
        private val _uiState = MutableStateFlow(ProfileImportConfirmUiState())
        val uiState: StateFlow<ProfileImportConfirmUiState> = _uiState.asStateFlow()

        /** Seeds the screen with the [profile] parsed from the inbound share link. */
        fun setProfile(profile: ProxyProfile) {
            _uiState.update { it.copy(profile = profile) }
        }

        /**
         * Persists the parsed profile into a new single-profile group and activates it
         * as the native relay. No-op when there is no profile to import or an import is
         * already in flight.
         */
        fun confirm() {
            val profile = _uiState.value.profile ?: return
            if (_uiState.value.importing || _uiState.value.imported) return
            _uiState.update { it.copy(importing = true) }
            viewModelScope.launch {
                activator.activate(profile)
                _uiState.update { it.copy(importing = false, imported = true) }
            }
        }
    }
