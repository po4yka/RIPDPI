package com.poyka.ripdpi.ui.screens.proxyimport

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
import java.util.UUID
import javax.inject.Inject

/**
 * Persists a parsed [ProxyProfile] as the live native relay and records a
 * single-profile [ProxyGroupType.BASIC] group for it.
 *
 * This is the one place that maps a foreign-exit [ProxyProfile] onto the native
 * relay engine's [RelayProfileRecord] / [RelayCredentialRecord] / `AppSettings`
 * triple (profile id [DefaultRelayProfileId]). It is shared by the share-link
 * import-confirmation screen ([ProfileImportConfirmViewModel]) and the Xray
 * config import flow so the two paths cannot drift.
 *
 * Only the four relay kinds with a native backend reachable from an import
 * ([ProxyProfile.VlessReality], [ProxyProfile.Trojan], [ProxyProfile.Shadowsocks],
 * [ProxyProfile.AnyTls]) activate the relay; any other profile still gets a group
 * marker but does not enable the relay (so it never yields a connection that
 * fails at connect time). Use [supports] to pre-filter.
 */
class NativeRelayProfileActivator
    @Inject
    constructor(
        private val repository: ProxyGroupRepository,
        private val relayProfileStore: RelayProfileStore,
        private val relayCredentialStore: RelayCredentialStore,
        private val settingsRepository: AppSettingsRepository,
    ) {
        /** True when [profile] maps to a native relay backend reachable from import. */
        fun supports(profile: ProxyProfile): Boolean =
            when (profile) {
                is ProxyProfile.Trojan,
                is ProxyProfile.Shadowsocks,
                is ProxyProfile.AnyTls,
                is ProxyProfile.VlessReality,
                -> true

                else -> false
            }

        /**
         * Adds a single-profile [ProxyGroupType.BASIC] group for [profile] and, when
         * [supports] is true, activates it as the live native relay. Suspends until
         * the stores and settings have been written.
         */
        suspend fun activate(profile: ProxyProfile) {
            val groupId = UUID.randomUUID().toString()
            repository.add(
                ProxyGroup(
                    id = groupId,
                    name = profile.displayName,
                    type = ProxyGroupType.BASIC,
                    order = repository.list().size,
                    isSelector = false,
                    subscription = null,
                ),
            )
            activateNativeRelayProfile(profile)
        }

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
                    RelayImportEndpoint(profile.server, profile.serverPort, profile.server)
                }

                is ProxyProfile.Shadowsocks -> {
                    RelayImportEndpoint(profile.server, profile.serverPort, profile.server)
                }

                is ProxyProfile.AnyTls -> {
                    RelayImportEndpoint(profile.server, profile.serverPort, profile.serverName)
                }

                is ProxyProfile.VlessReality -> {
                    RelayImportEndpoint(profile.server, profile.serverPort, profile.serverName)
                }

                else -> {
                    // Vless, Hysteria2, Mieru, Ssh, RawConfig — not relay-importable.
                    error("unsupported relay import profile ${profile::class.simpleName}")
                }
            }

        private fun relayCredentials(
            profileId: String,
            profile: ProxyProfile,
        ): RelayCredentialRecord =
            when (profile) {
                is ProxyProfile.Trojan -> {
                    RelayCredentialRecord(profileId = profileId, trojanPassword = profile.password)
                }

                is ProxyProfile.Shadowsocks -> {
                    RelayCredentialRecord(
                        profileId = profileId,
                        shadowsocksMethod = profile.method,
                        shadowsocksPassword = profile.password,
                    )
                }

                is ProxyProfile.AnyTls -> {
                    RelayCredentialRecord(profileId = profileId, anyTlsPassword = profile.password)
                }

                is ProxyProfile.VlessReality -> {
                    RelayCredentialRecord(profileId = profileId, vlessUuid = profile.uuid)
                }

                else -> {
                    error("unsupported relay import profile ${profile::class.simpleName}")
                }
            }
    }

private data class RelayImportEndpoint(
    val server: String,
    val serverPort: Int,
    val serverName: String,
)
