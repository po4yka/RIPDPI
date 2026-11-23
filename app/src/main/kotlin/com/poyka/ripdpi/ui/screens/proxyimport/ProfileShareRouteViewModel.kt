package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.LogTags
import com.poyka.ripdpi.data.ProfileMutationRecoveryAccess
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRepository
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.proxyimport.RelayProfileShareMapper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileShareRouteUiState(
    val loading: Boolean = true,
    val profileId: String = "",
    val profile: ProxyProfile? = null,
    val shareUri: String? = null,
    val unshareable: Boolean = false,
)

@HiltViewModel
class ProfileShareRouteViewModel
    @Inject
    constructor(
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialRepository: RelayCredentialRepository,
        private val profileRecovery: ProfileMutationRecoveryAccess,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ProfileShareRouteUiState())
        val uiState: StateFlow<ProfileShareRouteUiState> = _uiState.asStateFlow()
        private var loadJob: Job? = null

        fun load(profileId: String) {
            if (_uiState.value.profileId == profileId && !_uiState.value.loading) return
            loadJob?.cancel()
            _uiState.update { ProfileShareRouteUiState(profileId = profileId, loading = true) }
            loadJob =
                viewModelScope.launch {
                    val shareable =
                        try {
                            val snapshot =
                                profileRecovery.readRecovered {
                                    val record = relayProfileStore.load(profileId) ?: return@readRecovered null
                                    record to relayCredentialRepository.load(profileId)
                                }
                            snapshot?.let { (record, credentials) ->
                                RelayProfileShareMapper.toShareable(record, credentials)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (ignored: Exception) {
                            // An undecodable record (for example after a downgrade) is unshareable, not a crash.
                            Logger.w(
                                tag = LogTags.UI,
                            ) { "Relay profile share load failed: ${ignored.javaClass.simpleName}" }
                            null
                        }
                    ensureActive()
                    _uiState.value =
                        ProfileShareRouteUiState(
                            loading = false,
                            profileId = profileId,
                            profile = shareable?.profile,
                            shareUri = shareable?.shareUri,
                            unshareable = shareable == null,
                        )
                }
        }
    }
