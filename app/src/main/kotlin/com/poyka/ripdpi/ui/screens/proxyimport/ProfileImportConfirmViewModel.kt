package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.validateNativeRelayProfileResult
import com.poyka.ripdpi.proxyimport.PendingProxyImportStore
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
import java.util.UUID
import javax.inject.Inject

/** UI state for the single-profile import-confirmation screen. */
data class ProfileImportConfirmUiState(
    val profile: ProxyProfile? = null,
    val importing: Boolean = false,
    /** String resource to show when validation/activation failed; `null` when there is no error. */
    @StringRes val errorRes: Int? = null,
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
            completed = false
            _uiState.value = ProfileImportConfirmUiState(profile = pendingImports.claim(importToken))
            return _uiState.value.profile != null
        }

        /** Seeds the screen with the [profile] parsed from the inbound share link. */
        fun setProfile(profile: ProxyProfile) {
            completed = false
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
            if (_uiState.value.importing || completed) return
            _uiState.update { it.copy(importing = true, errorRes = null) }
            viewModelScope.launch {
                // Validation throws IllegalArgumentException on invalid field
                // material; persistence/activation can also throw. Capture both as
                // a Result so the failure surfaces in the UI instead of escaping
                // the coroutine as an uncaught crash. mapCatching is inline, so the
                // suspend persistence calls run inside this coroutine context.
                //
                // Every profile that reaches this screen was parsed from a
                // relay-protocol share link (vless/tuic/ss/trojan/ssh/...) and is
                // expected to tunnel. activate() returns false (a no-op) only for
                // kinds that build NO native relay backend — plain ProxyProfile.Vless
                // without XHTTP (and no REALITY material) and ProxyProfile.RawConfig (e.g. an imported
                // tuic:// link with no first-class subtype). There is no legitimate
                // "store-only, non-relay" import on this surface, so activate()==false
                // is a dead-end import, NOT a success: we must surface an error and
                // roll back the just-persisted group instead of leaving a phantom
                // entry the user thinks is working. Only activate()==true is honest
                // success.
                val groupId = UUID.randomUUID().toString()
                val result =
                    validateNativeRelayProfileResult(profile).mapCatching {
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
                        relayActivator.activate(profile).also { activated ->
                            if (!activated) {
                                // Roll back the phantom group so a dead-end import
                                // does not accumulate a non-working entry.
                                repository.delete(groupId)
                            }
                        }
                    }
                _uiState.update {
                    result.fold(
                        onSuccess = { activated ->
                            if (activated) {
                                it.copy(importing = false)
                            } else {
                                it.copy(importing = false, errorRes = R.string.import_profile_confirm_error)
                            }
                        },
                        onFailure = { _ ->
                            it.copy(importing = false, errorRes = R.string.import_profile_confirm_error)
                        },
                    )
                }
                if (result.getOrNull() == true) {
                    completed = true
                    importedEventChannel.send(Unit)
                }
            }
        }

        private suspend fun nextOrder(): Int = repository.list().size
    }
