package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

const val RelayKindOff = "off"
const val RelayKindVlessReality = "vless_reality"
const val RelayKindHysteria2 = "hysteria2"
const val RelayKindChainRelay = "chain_relay"
const val RelayKindMasque = "masque"
const val DefaultRelayProfileId = "default"
const val DefaultRelayLocalSocksHost = "127.0.0.1"
const val DefaultRelayLocalSocksPort = 11980

fun normalizeRelayKind(value: String): String =
    when (value.trim().lowercase()) {
        RelayKindVlessReality -> RelayKindVlessReality
        RelayKindHysteria2 -> RelayKindHysteria2
        RelayKindChainRelay -> RelayKindChainRelay
        RelayKindMasque -> RelayKindMasque
        else -> RelayKindOff
    }

data class RelayProfileModel(
    val id: String = DefaultRelayProfileId,
    val kind: String = RelayKindOff,
    val server: String = "",
    val serverPort: Int = 443,
    val serverName: String = "",
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val chainEntryServer: String = "",
    val chainEntryPort: Int = 443,
    val chainEntryServerName: String = "",
    val chainEntryPublicKey: String = "",
    val chainEntryShortId: String = "",
    val chainExitServer: String = "",
    val chainExitPort: Int = 443,
    val chainExitServerName: String = "",
    val chainExitPublicKey: String = "",
    val chainExitShortId: String = "",
    val masqueUrl: String = "",
    val masqueUseHttp2Fallback: Boolean = true,
    val masqueCloudflareMode: Boolean = false,
    val localSocksHost: String = DefaultRelayLocalSocksHost,
    val localSocksPort: Int = DefaultRelayLocalSocksPort,
    val udpEnabled: Boolean = false,
    val tcpFallbackEnabled: Boolean = true,
)

data class RelaySettingsModel(
    val enabled: Boolean = false,
    val kind: String = RelayKindOff,
    val profileId: String = DefaultRelayProfileId,
    val profile: RelayProfileModel = RelayProfileModel(),
)

fun AppSettings.toRelaySettingsModel(): RelaySettingsModel {
    val profileId = relayProfileId.ifBlank { DefaultRelayProfileId }
    val kind = normalizeRelayKind(relayKind)
    return RelaySettingsModel(
        enabled = relayEnabled && kind != RelayKindOff,
        kind = kind,
        profileId = profileId,
        profile =
            RelayProfileModel(
                id = profileId,
                kind = kind,
                server = relayServer,
                serverPort = relayServerPort.takeIf { it > 0 } ?: 443,
                serverName = relayServerName,
                realityPublicKey = relayRealityPublicKey,
                realityShortId = relayRealityShortId,
                chainEntryServer = relayChainEntryServer,
                chainEntryPort = relayChainEntryPort.takeIf { it > 0 } ?: 443,
                chainEntryServerName = relayChainEntryServerName,
                chainEntryPublicKey = relayChainEntryPublicKey,
                chainEntryShortId = relayChainEntryShortId,
                chainExitServer = relayChainExitServer,
                chainExitPort = relayChainExitPort.takeIf { it > 0 } ?: 443,
                chainExitServerName = relayChainExitServerName,
                chainExitPublicKey = relayChainExitPublicKey,
                chainExitShortId = relayChainExitShortId,
                masqueUrl = relayMasqueUrl,
                masqueUseHttp2Fallback = relayMasqueUseHttp2Fallback,
                masqueCloudflareMode = relayMasqueCloudflareMode,
                localSocksHost = relayLocalSocksHost.ifBlank { DefaultRelayLocalSocksHost },
                localSocksPort = relayLocalSocksPort.takeIf { it > 0 } ?: DefaultRelayLocalSocksPort,
                udpEnabled = relayUdpEnabled,
                tcpFallbackEnabled = relayTcpFallbackEnabled,
            ),
    )
}
