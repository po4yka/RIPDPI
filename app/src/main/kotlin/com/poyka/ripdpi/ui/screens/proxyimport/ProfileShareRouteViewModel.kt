package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRepository
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.proxyimport.RelayProfileShareMapper
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val setupSheet: String? = null,
    val unshareable: Boolean = false,
)

@HiltViewModel
class ProfileShareRouteViewModel
    @Inject
    constructor(
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialRepository: RelayCredentialRepository,
        private val stringResolver: StringResolver,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ProfileShareRouteUiState())
        val uiState: StateFlow<ProfileShareRouteUiState> = _uiState.asStateFlow()

        fun load(profileId: String) {
            if (_uiState.value.profileId == profileId && !_uiState.value.loading) return
            _uiState.update { ProfileShareRouteUiState(profileId = profileId, loading = true) }
            viewModelScope.launch {
                val record = relayProfileStore.load(profileId)
                val credentials = relayCredentialRepository.load(profileId)
                val shareable = record?.let { RelayProfileShareMapper.toShareable(it, credentials) }
                _uiState.value =
                    ProfileShareRouteUiState(
                        loading = false,
                        profileId = profileId,
                        profile = shareable?.profile,
                        shareUri = shareable?.shareUri,
                        setupSheet =
                            shareable?.let {
                                ProfileSetupSheetFormatter.format(
                                    strings = stringResolver,
                                    profileName = it.profile.displayName,
                                    profileKind = profileKindLabel(it.profile),
                                    shareUri = it.shareUri,
                                )
                            },
                        unshareable = shareable == null,
                    )
            }
        }
    }

object ProfileSetupSheetFormatter {
    fun format(
        strings: StringResolver,
        profileName: String,
        profileKind: String,
        shareUri: String,
    ): String =
        strings.getString(
            R.string.profile_share_setup_sheet_template,
            profileName,
            profileKind,
            shareUri,
        )
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
        is ProxyProfile.Ssh -> "SSH"
        is ProxyProfile.RawConfig -> "Raw URI"
    }
