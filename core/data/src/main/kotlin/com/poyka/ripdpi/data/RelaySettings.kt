package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

const val RelayKindOff = "off"
const val RelayKindVlessReality = "vless_reality"
const val RelayKindHysteria2 = "hysteria2"
const val RelayKindChainRelay = "chain_relay"
const val RelayKindMasque = "masque"
const val RelayKindCloudflareTunnel = "cloudflare_tunnel"
const val RelayKindTuicV5 = "tuic_v5"
const val RelayKindShadowTlsV3 = "shadowtls_v3"
const val RelayKindNaiveProxy = "naiveproxy"
const val RelayKindGoogleAppsScript = "google_apps_script"
const val RelayKindSnowflake = "snowflake"
const val RelayKindWebTunnel = "webtunnel"
const val RelayKindObfs4 = "obfs4"
const val RelayVlessTransportRealityTcp = "reality_tcp"
const val RelayVlessTransportXhttp = "xhttp"
const val RelayMasqueAuthModeBearer = "bearer"
const val RelayMasqueAuthModePreshared = "preshared"
const val RelayMasqueAuthModePrivacyPass = "privacy_pass"
const val RelayMasqueAuthModeCloudflareMtls = "cloudflare_mtls"
const val RelayCloudflareTunnelModeConsumeExisting = "consume_existing"
const val RelayCloudflareTunnelModePublishLocalOrigin = "publish_local_origin"
const val RelayFinalmaskTypeOff = "off"
const val RelayFinalmaskTypeHeaderCustom = "header_custom"
const val RelayFinalmaskTypeSudoku = "sudoku"
const val RelayFinalmaskTypeFragment = "fragment"
const val RelayFinalmaskTypeNoise = "noise"
const val RelayCongestionControlBbr = "bbr"
const val RelayCongestionControlCubic = "cubic"
const val DefaultRelayProfileId = "default"
const val DefaultRelayLocalSocksHost = "127.0.0.1"
const val DefaultRelayLocalSocksPort = 11980
const val DefaultRelayAppsScriptVerifySsl = true
const val DefaultSnowflakeBrokerUrl = "https://snowflake-broker.torproject.net/"
const val DefaultSnowflakeFrontDomain = "cdn.sstatic.net"

fun normalizeRelayKind(value: String): String =
    when (value.trim().lowercase()) {
        RelayKindVlessReality -> RelayKindVlessReality
        RelayKindHysteria2 -> RelayKindHysteria2
        RelayKindChainRelay -> RelayKindChainRelay
        RelayKindMasque -> RelayKindMasque
        RelayKindCloudflareTunnel -> RelayKindCloudflareTunnel
        RelayKindTuicV5 -> RelayKindTuicV5
        RelayKindShadowTlsV3 -> RelayKindShadowTlsV3
        RelayKindNaiveProxy -> RelayKindNaiveProxy
        RelayKindGoogleAppsScript -> RelayKindGoogleAppsScript
        RelayKindSnowflake -> RelayKindSnowflake
        RelayKindWebTunnel -> RelayKindWebTunnel
        RelayKindObfs4 -> RelayKindObfs4
        else -> RelayKindOff
    }

fun normalizeRelayStringList(values: Iterable<String>): List<String> =
    values
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()

fun normalizeRelayVlessTransport(
    value: String,
    relayKind: String? = null,
): String =
    when {
        normalizeRelayKind(relayKind.orEmpty()) == RelayKindCloudflareTunnel -> RelayVlessTransportXhttp
        value.trim().lowercase() == RelayVlessTransportXhttp -> RelayVlessTransportXhttp
        else -> RelayVlessTransportRealityTcp
    }

fun normalizeRelayMasqueAuthMode(value: String?): String? =
    when (value?.trim()?.lowercase()) {
        RelayMasqueAuthModeBearer,
        "token",
        -> RelayMasqueAuthModeBearer

        RelayMasqueAuthModePreshared -> RelayMasqueAuthModePreshared

        RelayMasqueAuthModePrivacyPass -> RelayMasqueAuthModePrivacyPass

        RelayMasqueAuthModeCloudflareMtls -> RelayMasqueAuthModeCloudflareMtls

        null,
        "",
        -> null

        else -> null
    }

fun normalizeRelayCongestionControl(value: String?): String =
    when (value?.trim()?.lowercase()) {
        RelayCongestionControlCubic -> RelayCongestionControlCubic
        else -> RelayCongestionControlBbr
    }

fun normalizeRelayCloudflareTunnelMode(value: String?): String =
    when (value?.trim()?.lowercase()) {
        RelayCloudflareTunnelModePublishLocalOrigin -> RelayCloudflareTunnelModePublishLocalOrigin
        else -> RelayCloudflareTunnelModeConsumeExisting
    }

fun normalizeRelayFinalmaskType(value: String?): String =
    when (value?.trim()?.lowercase()) {
        RelayFinalmaskTypeHeaderCustom -> RelayFinalmaskTypeHeaderCustom
        RelayFinalmaskTypeSudoku -> RelayFinalmaskTypeSudoku
        RelayFinalmaskTypeFragment -> RelayFinalmaskTypeFragment
        RelayFinalmaskTypeNoise -> RelayFinalmaskTypeNoise
        else -> RelayFinalmaskTypeOff
    }

data class RelayFinalmaskModel(
    val type: String = RelayFinalmaskTypeOff,
    val headerHex: String = "",
    val trailerHex: String = "",
    val randRange: String = "",
    val sudokuSeed: String = "",
    val fragmentPackets: Int = 0,
    val fragmentMinBytes: Int = 0,
    val fragmentMaxBytes: Int = 0,
)

data class RelayProfileModel(
    val id: String = DefaultRelayProfileId,
    val kind: String = RelayKindOff,
    val presetId: String = "",
    val outboundBindIp: String = "",
    val server: String = "",
    val serverPort: Int = 443,
    val serverName: String = "",
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val vlessTransport: String = RelayVlessTransportRealityTcp,
    val xhttpPath: String = "",
    val xhttpHost: String = "",
    val cloudflareTunnelMode: String = RelayCloudflareTunnelModeConsumeExisting,
    val cloudflarePublishLocalOriginUrl: String = "",
    val cloudflareCredentialsRef: String = "",
    val chainEntryServer: String = "",
    val chainEntryPort: Int = 443,
    val chainEntryServerName: String = "",
    val chainEntryPublicKey: String = "",
    val chainEntryShortId: String = "",
    val chainEntryProfileId: String = "",
    val chainExitServer: String = "",
    val chainExitPort: Int = 443,
    val chainExitServerName: String = "",
    val chainExitPublicKey: String = "",
    val chainExitShortId: String = "",
    val chainExitProfileId: String = "",
    val masqueUrl: String = "",
    val masqueUseHttp2Fallback: Boolean = true,
    val masqueCloudflareGeohashEnabled: Boolean = false,
    val tuicZeroRtt: Boolean = false,
    val tuicCongestionControl: String = RelayCongestionControlBbr,
    val shadowTlsInnerProfileId: String = "",
    val naivePath: String = "",
    val appsScriptScriptIds: List<String> = emptyList(),
    val appsScriptGoogleIp: String = "",
    val appsScriptFrontDomain: String = "",
    val appsScriptSniHosts: List<String> = emptyList(),
    val appsScriptVerifySsl: Boolean = DefaultRelayAppsScriptVerifySsl,
    val appsScriptParallelRelay: Boolean = false,
    val appsScriptDirectHosts: List<String> = emptyList(),
    val ptBridgeLine: String = "",
    val ptWebTunnelUrl: String = "",
    val ptSnowflakeBrokerUrl: String = DefaultSnowflakeBrokerUrl,
    val ptSnowflakeFrontDomain: String = DefaultSnowflakeFrontDomain,
    val localSocksHost: String = DefaultRelayLocalSocksHost,
    val localSocksPort: Int = DefaultRelayLocalSocksPort,
    val udpEnabled: Boolean = false,
    val tcpFallbackEnabled: Boolean = true,
    val finalmask: RelayFinalmaskModel = RelayFinalmaskModel(),
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
                presetId = "",
                outboundBindIp = relayOutboundBindIp,
                server = relayServer,
                serverPort = relayServerPort.takeIf { it > 0 } ?: 443,
                serverName = relayServerName,
                realityPublicKey = relayRealityPublicKey,
                realityShortId = relayRealityShortId,
                vlessTransport = normalizeRelayVlessTransport(relayVlessTransport, kind),
                xhttpPath = relayXhttpPath,
                xhttpHost = relayXhttpHost,
                cloudflareTunnelMode = normalizeRelayCloudflareTunnelMode(relayCloudflareTunnelMode),
                cloudflarePublishLocalOriginUrl = relayCloudflarePublishLocalOriginUrl,
                cloudflareCredentialsRef = relayCloudflareCredentialsRef,
                chainEntryServer = relayChainEntryServer,
                chainEntryPort = relayChainEntryPort.takeIf { it > 0 } ?: 443,
                chainEntryServerName = relayChainEntryServerName,
                chainEntryPublicKey = relayChainEntryPublicKey,
                chainEntryShortId = relayChainEntryShortId,
                chainEntryProfileId = relayChainEntryProfileId,
                chainExitServer = relayChainExitServer,
                chainExitPort = relayChainExitPort.takeIf { it > 0 } ?: 443,
                chainExitServerName = relayChainExitServerName,
                chainExitPublicKey = relayChainExitPublicKey,
                chainExitShortId = relayChainExitShortId,
                chainExitProfileId = relayChainExitProfileId,
                masqueUrl = relayMasqueUrl,
                masqueUseHttp2Fallback = relayMasqueUseHttp2Fallback,
                masqueCloudflareGeohashEnabled = relayMasqueCloudflareGeohashEnabled,
                tuicZeroRtt = relayTuicZeroRtt,
                tuicCongestionControl = normalizeRelayCongestionControl(relayTuicCongestionControl),
                shadowTlsInnerProfileId = relayShadowtlsInnerProfileId,
                naivePath = relayNaivePath,
                appsScriptScriptIds = normalizeRelayStringList(relayAppsScriptScriptIdsList),
                appsScriptGoogleIp = relayAppsScriptGoogleIp.trim(),
                appsScriptFrontDomain = relayAppsScriptFrontDomain.trim(),
                appsScriptSniHosts = normalizeRelayStringList(relayAppsScriptSniHostsList),
                appsScriptVerifySsl = relayAppsScriptVerifySsl,
                appsScriptParallelRelay = relayAppsScriptParallelRelay,
                appsScriptDirectHosts = normalizeRelayStringList(relayAppsScriptDirectHostsList),
                ptBridgeLine = "",
                ptWebTunnelUrl = "",
                ptSnowflakeBrokerUrl = DefaultSnowflakeBrokerUrl,
                ptSnowflakeFrontDomain = DefaultSnowflakeFrontDomain,
                localSocksHost = relayLocalSocksHost.ifBlank { DefaultRelayLocalSocksHost },
                localSocksPort = relayLocalSocksPort.takeIf { it > 0 } ?: DefaultRelayLocalSocksPort,
                udpEnabled = relayUdpEnabled,
                tcpFallbackEnabled = relayTcpFallbackEnabled,
                finalmask =
                    RelayFinalmaskModel(
                        type = normalizeRelayFinalmaskType(relayFinalmaskType),
                        headerHex = relayFinalmaskHeaderHex,
                        trailerHex = relayFinalmaskTrailerHex,
                        randRange = relayFinalmaskRandRange,
                        sudokuSeed = relayFinalmaskSudokuSeed,
                        fragmentPackets = relayFinalmaskFragmentPackets,
                        fragmentMinBytes = relayFinalmaskFragmentMinBytes,
                        fragmentMaxBytes = relayFinalmaskFragmentMaxBytes,
                    ),
            ),
    )
}
