package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRepository
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.proxyimport.RelayProfileShareMapper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ProfileShareRouteUiState())
        val uiState: StateFlow<ProfileShareRouteUiState> = _uiState.asStateFlow()

        fun load(profileId: String) {
            if (_uiState.value.profileId == profileId && !_uiState.value.loading) return
            _uiState.update { ProfileShareRouteUiState(profileId = profileId, loading = true) }
            viewModelScope.launch {
                val shareable =
                    try {
                        val record = relayProfileStore.load(profileId)
                        val credentials = relayCredentialRepository.load(profileId)
                        record?.let { RelayProfileShareMapper.toShareable(it, credentials) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (ignored: Exception) {
                        // An undecodable record (for example after a downgrade) is unshareable, not a crash.
                        Logger.w { "Relay profile share load failed: ${ignored.javaClass.simpleName}" }
                        null
                    }
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

internal fun profileKindLabel(profile: ProxyProfile): String =
    when (profile) {
        is ProxyProfile.Vless -> "VLESS"
        is ProxyProfile.VlessReality -> "VLESS + REALITY"
        is ProxyProfile.Shadowsocks -> "Shadowsocks"
        is ProxyProfile.Trojan -> "Trojan"
        is ProxyProfile.Hysteria2 -> "Hysteria2"
        is ProxyProfile.AnyTls -> "AnyTLS"
        is ProxyProfile.Mieru -> "Mieru"
        is ProxyProfile.Naive -> "NaiveProxy"
        is ProxyProfile.Ssh -> "SSH"
        is ProxyProfile.RawConfig -> "Raw URI"
    }
