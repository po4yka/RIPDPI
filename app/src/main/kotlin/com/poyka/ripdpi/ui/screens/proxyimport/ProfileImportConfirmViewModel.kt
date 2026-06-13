package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.proxyimport.RelayProfileActivator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
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
 * group via [ProxyGroupRepository] and, for relay-activatable kinds, applies it as
 * the active native relay through [RelayProfileActivator]. The handler activity
 * navigates here after parsing a `vless://` / `ss://` / `trojan://` / `ssh://`
 * style share link.
 */
@HiltViewModel
class ProfileImportConfirmViewModel
    @Inject
    constructor(
        private val repository: ProxyGroupRepository,
        private val relayActivator: RelayProfileActivator,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ProfileImportConfirmUiState())
        val uiState: StateFlow<ProfileImportConfirmUiState> = _uiState.asStateFlow()

        /** Seeds the screen with the [profile] parsed from the inbound share link. */
        fun setProfile(profile: ProxyProfile) {
            _uiState.update { it.copy(profile = profile) }
        }

        /**
         * Persists the parsed profile into a new single-profile group and activates it
         * as the native relay (when the kind is relay-activatable). The group is stamped
         * with the generated id so it is attributable. No-op when there is no profile to
         * import or an import is already in flight.
         */
        fun confirm() {
            val profile = _uiState.value.profile ?: return
            if (_uiState.value.importing || _uiState.value.imported) return
            _uiState.update { it.copy(importing = true) }
            viewModelScope.launch {
                val groupId = UUID.randomUUID().toString()
                repository.add(
                    ProxyGroup(
                        id = groupId,
                        name = profile.displayName,
                        type = ProxyGroupType.BASIC,
                        order = nextOrder(),
                        isSelector = false,
                        subscription = null,
                    ),
                )
                relayActivator.activate(profile)
                _uiState.update { it.copy(importing = false, imported = true) }
            }
        }

        private suspend fun nextOrder(): Int = repository.list().size
    }
