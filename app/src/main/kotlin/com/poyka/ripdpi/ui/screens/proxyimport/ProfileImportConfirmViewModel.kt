package com.poyka.ripdpi.ui.screens.proxyimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.ProxyGroupType
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayCredentialStore
import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayKindShadowsocks
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayProfileStore
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import com.poyka.ripdpi.data.RelayVlessTransportXhttp
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
 * group via [ProxyGroupRepository]. The handler activity navigates here after parsing a
 * `vless://` / `ss://` / `trojan://` style share link.
 */
@HiltViewModel
class ProfileImportConfirmViewModel
    @Inject
    constructor(
        private val repository: ProxyGroupRepository,
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialStore: RelayCredentialStore,
        private val settingsRepository: AppSettingsRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ProfileImportConfirmUiState())
        val uiState: StateFlow<ProfileImportConfirmUiState> = _uiState.asStateFlow()

        /** Seeds the screen with the [profile] parsed from the inbound share link. */
        fun setProfile(profile: ProxyProfile) {
            _uiState.update { it.copy(profile = profile) }
        }

        /**
         * Persists the parsed profile into a new single-profile group. The profile is
         * stamped with the generated group id so it is attributable. No-op when there is
         * no profile to import or an import is already in flight.
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
                activateNativeRelayProfile(profile)
                _uiState.update { it.copy(importing = false, imported = true) }
            }
        }

        private suspend fun nextOrder(): Int = repository.list().size

        private suspend fun activateNativeRelayProfile(profile: ProxyProfile) {
            // Unsupported profile kinds are filtered by the `else -> return` arm below.
            val profileId = DefaultRelayProfileId
            val relayKind =
                when (profile) {
                    is ProxyProfile.Trojan -> RelayKindTrojan
                    is ProxyProfile.Shadowsocks -> RelayKindShadowsocks
                    is ProxyProfile.AnyTls -> RelayKindAnyTls
                    is ProxyProfile.VlessReality -> RelayKindVlessReality
                    else -> return
                }
            val endpoint = relayEndpoint(profile)
            val vlessTransport =
                if (profile is ProxyProfile.VlessReality && profile.xhttpPath != null) {
                    RelayVlessTransportXhttp
                } else {
                    RelayVlessTransportRealityTcp
                }
            relayProfileStore.save(
                RelayProfileRecord(
                    id = profileId,
                    kind = relayKind,
                    server = endpoint.server,
                    serverPort = endpoint.serverPort,
                    serverName = endpoint.serverName,
                    realityPublicKey = if (profile is ProxyProfile.VlessReality) profile.realityPublicKey else "",
                    realityShortId = if (profile is ProxyProfile.VlessReality) profile.realityShortId else "",
                    vlessTransport = vlessTransport,
                    xhttpPath = if (profile is ProxyProfile.VlessReality) profile.xhttpPath.orEmpty() else "",
                    xhttpHost = if (profile is ProxyProfile.VlessReality) profile.xhttpHost.orEmpty() else "",
                    udpEnabled = true,
                ),
            )
            relayCredentialStore.save(
                relayCredentials(profileId, profile),
            )
            settingsRepository.update {
                setRelayEnabled(true)
                setRelayKind(relayKind)
                setRelayProfileId(profileId)
                setRelayServer(endpoint.server)
                setRelayServerPort(endpoint.serverPort)
                setRelayServerName(endpoint.serverName)
                setRelayUdpEnabled(true)
                if (profile is ProxyProfile.VlessReality) {
                    setRelayRealityPublicKey(profile.realityPublicKey)
                    setRelayRealityShortId(profile.realityShortId)
                    setRelayVlessTransport(vlessTransport)
                    setRelayXhttpPath(profile.xhttpPath.orEmpty())
                    setRelayXhttpHost(profile.xhttpHost.orEmpty())
                }
            }
        }

        private fun relayEndpoint(profile: ProxyProfile): RelayImportEndpoint =
            when (profile) {
                is ProxyProfile.Trojan -> {
                    RelayImportEndpoint(
                        server = profile.server,
                        serverPort = profile.serverPort,
                        serverName = profile.server,
                    )
                }

                is ProxyProfile.Shadowsocks -> {
                    RelayImportEndpoint(
                        server = profile.server,
                        serverPort = profile.serverPort,
                        serverName = profile.server,
                    )
                }

                is ProxyProfile.AnyTls -> {
                    RelayImportEndpoint(
                        server = profile.server,
                        serverPort = profile.serverPort,
                        serverName = profile.serverName,
                    )
                }

                is ProxyProfile.VlessReality -> {
                    RelayImportEndpoint(
                        server = profile.server,
                        serverPort = profile.serverPort,
                        serverName = profile.serverName,
                    )
                }

                else -> {
                    // Vless, Hysteria2, TrojanGo, RawConfig — not relay-importable
                    error("unsupported relay import profile ${profile::class.simpleName}")
                }
            }

        private fun relayCredentials(
            profileId: String,
            profile: ProxyProfile,
        ): RelayCredentialRecord =
            when (profile) {
                is ProxyProfile.Trojan -> {
                    RelayCredentialRecord(
                        profileId = profileId,
                        trojanPassword = profile.password,
                    )
                }

                is ProxyProfile.Shadowsocks -> {
                    RelayCredentialRecord(
                        profileId = profileId,
                        shadowsocksMethod = profile.method,
                        shadowsocksPassword = profile.password,
                    )
                }

                is ProxyProfile.AnyTls -> {
                    RelayCredentialRecord(
                        profileId = profileId,
                        anyTlsPassword = profile.password,
                    )
                }

                is ProxyProfile.VlessReality -> {
                    RelayCredentialRecord(
                        profileId = profileId,
                        vlessUuid = profile.uuid,
                    )
                }

                else -> {
                    // Vless, Hysteria2, TrojanGo, RawConfig — not relay-importable
                    error("unsupported relay import profile ${profile::class.simpleName}")
                }
            }
    }

private data class RelayImportEndpoint(
    val server: String,
    val serverPort: Int,
    val serverName: String,
)
