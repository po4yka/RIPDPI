package com.poyka.ripdpi.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

internal data class AppSettingsRelaySnapshot(
    val relayEnabled: Boolean = defaultSettings.relayEnabled,
    val relayKind: String = defaultSettings.relayKind,
    val relayProfileId: String = defaultSettings.relayProfileId,
    val relayOutboundBindIp: String = defaultSettings.relayOutboundBindIp,
    val relayServer: String = defaultSettings.relayServer,
    val relayServerPort: Int = defaultSettings.relayServerPort,
    val relayServerName: String = defaultSettings.relayServerName,
    val relayRealityPublicKey: String = defaultSettings.relayRealityPublicKey,
    val relayRealityShortId: String = defaultSettings.relayRealityShortId,
    val relayVlessTransport: String = defaultSettings.relayVlessTransport,
    val relayXhttpPath: String = defaultSettings.relayXhttpPath,
    val relayXhttpHost: String = defaultSettings.relayXhttpHost,
    val relayCloudflareTunnelMode: String = defaultSettings.relayCloudflareTunnelMode,
    val relayCloudflarePublishLocalOriginUrl: String = defaultSettings.relayCloudflarePublishLocalOriginUrl,
    val relayCloudflareCredentialsRef: String = defaultSettings.relayCloudflareCredentialsRef,
    val relayChainEntryServer: String = defaultSettings.relayChainEntryServer,
    val relayChainEntryPort: Int = defaultSettings.relayChainEntryPort,
    val relayChainEntryServerName: String = defaultSettings.relayChainEntryServerName,
    val relayChainEntryPublicKey: String = defaultSettings.relayChainEntryPublicKey,
    val relayChainEntryShortId: String = defaultSettings.relayChainEntryShortId,
    val relayChainEntryProfileId: String = defaultSettings.relayChainEntryProfileId,
    val relayChainExitServer: String = defaultSettings.relayChainExitServer,
    val relayChainExitPort: Int = defaultSettings.relayChainExitPort,
    val relayChainExitServerName: String = defaultSettings.relayChainExitServerName,
    val relayChainExitPublicKey: String = defaultSettings.relayChainExitPublicKey,
    val relayChainExitShortId: String = defaultSettings.relayChainExitShortId,
    val relayChainExitProfileId: String = defaultSettings.relayChainExitProfileId,
    val relayMasqueUrl: String = defaultSettings.relayMasqueUrl,
    val relayMasqueUseHttp2Fallback: Boolean = defaultSettings.relayMasqueUseHttp2Fallback,
    val relayMasqueCloudflareGeohashEnabled: Boolean = defaultSettings.relayMasqueCloudflareGeohashEnabled,
    val relayTuicZeroRtt: Boolean = defaultSettings.relayTuicZeroRtt,
    val relayTuicCongestionControl: String = defaultSettings.relayTuicCongestionControl,
    val relayShadowTlsInnerProfileId: String = defaultSettings.relayShadowtlsInnerProfileId,
    val relayNaivePath: String = defaultSettings.relayNaivePath,
    val relayAppsScriptScriptIds: List<String> = defaultSettings.relayAppsScriptScriptIdsList,
    val relayAppsScriptGoogleIp: String = defaultSettings.relayAppsScriptGoogleIp,
    val relayAppsScriptFrontDomain: String = defaultSettings.relayAppsScriptFrontDomain,
    val relayAppsScriptSniHosts: List<String> = defaultSettings.relayAppsScriptSniHostsList,
    val relayAppsScriptVerifySsl: Boolean = defaultSettings.relayAppsScriptVerifySsl,
    val relayAppsScriptParallelRelay: Boolean = defaultSettings.relayAppsScriptParallelRelay,
    val relayAppsScriptDirectHosts: List<String> = defaultSettings.relayAppsScriptDirectHostsList,
    val relayLocalSocksHost: String = defaultSettings.relayLocalSocksHost,
    val relayLocalSocksPort: Int = defaultSettings.relayLocalSocksPort,
    val relayUdpEnabled: Boolean = defaultSettings.relayUdpEnabled,
    val relayTcpFallbackEnabled: Boolean = defaultSettings.relayTcpFallbackEnabled,
    val relayFinalmaskType: String = defaultSettings.relayFinalmaskType,
    val relayFinalmaskHeaderHex: String = defaultSettings.relayFinalmaskHeaderHex,
    val relayFinalmaskTrailerHex: String = defaultSettings.relayFinalmaskTrailerHex,
    val relayFinalmaskRandRange: String = defaultSettings.relayFinalmaskRandRange,
    val relayFinalmaskSudokuSeed: String = defaultSettings.relayFinalmaskSudokuSeed,
    val relayFinalmaskFragmentPackets: Int = defaultSettings.relayFinalmaskFragmentPackets,
    val relayFinalmaskFragmentMinBytes: Int = defaultSettings.relayFinalmaskFragmentMinBytes,
    val relayFinalmaskFragmentMaxBytes: Int = defaultSettings.relayFinalmaskFragmentMaxBytes,
)

@Suppress("LongMethod")
internal fun JsonObjectBuilder.writeRelaySnapshot(snapshot: AppSettingsRelaySnapshot) {
    put("relayEnabled", snapshot.relayEnabled)
    put("relayKind", snapshot.relayKind)
    put("relayProfileId", snapshot.relayProfileId)
    put("relayOutboundBindIp", snapshot.relayOutboundBindIp)
    put("relayServer", snapshot.relayServer)
    put("relayServerPort", snapshot.relayServerPort)
    put("relayServerName", snapshot.relayServerName)
    put("relayRealityPublicKey", snapshot.relayRealityPublicKey)
    put("relayRealityShortId", snapshot.relayRealityShortId)
    put("relayVlessTransport", snapshot.relayVlessTransport)
    put("relayXhttpPath", snapshot.relayXhttpPath)
    put("relayXhttpHost", snapshot.relayXhttpHost)
    put("relayCloudflareTunnelMode", snapshot.relayCloudflareTunnelMode)
    put("relayCloudflarePublishLocalOriginUrl", snapshot.relayCloudflarePublishLocalOriginUrl)
    put("relayCloudflareCredentialsRef", snapshot.relayCloudflareCredentialsRef)
    put("relayChainEntryServer", snapshot.relayChainEntryServer)
    put("relayChainEntryPort", snapshot.relayChainEntryPort)
    put("relayChainEntryServerName", snapshot.relayChainEntryServerName)
    put("relayChainEntryPublicKey", snapshot.relayChainEntryPublicKey)
    put("relayChainEntryShortId", snapshot.relayChainEntryShortId)
    put("relayChainEntryProfileId", snapshot.relayChainEntryProfileId)
    put("relayChainExitServer", snapshot.relayChainExitServer)
    put("relayChainExitPort", snapshot.relayChainExitPort)
    put("relayChainExitServerName", snapshot.relayChainExitServerName)
    put("relayChainExitPublicKey", snapshot.relayChainExitPublicKey)
    put("relayChainExitShortId", snapshot.relayChainExitShortId)
    put("relayChainExitProfileId", snapshot.relayChainExitProfileId)
    put("relayMasqueUrl", snapshot.relayMasqueUrl)
    put("relayMasqueUseHttp2Fallback", snapshot.relayMasqueUseHttp2Fallback)
    put("relayMasqueCloudflareGeohashEnabled", snapshot.relayMasqueCloudflareGeohashEnabled)
    put("relayTuicZeroRtt", snapshot.relayTuicZeroRtt)
    put("relayTuicCongestionControl", snapshot.relayTuicCongestionControl)
    put("relayShadowTlsInnerProfileId", snapshot.relayShadowTlsInnerProfileId)
    put("relayNaivePath", snapshot.relayNaivePath)
    putStringList("relayAppsScriptScriptIds", snapshot.relayAppsScriptScriptIds)
    put("relayAppsScriptGoogleIp", snapshot.relayAppsScriptGoogleIp)
    put("relayAppsScriptFrontDomain", snapshot.relayAppsScriptFrontDomain)
    putStringList("relayAppsScriptSniHosts", snapshot.relayAppsScriptSniHosts)
    put("relayAppsScriptVerifySsl", snapshot.relayAppsScriptVerifySsl)
    put("relayAppsScriptParallelRelay", snapshot.relayAppsScriptParallelRelay)
    putStringList("relayAppsScriptDirectHosts", snapshot.relayAppsScriptDirectHosts)
    put("relayLocalSocksHost", snapshot.relayLocalSocksHost)
    put("relayLocalSocksPort", snapshot.relayLocalSocksPort)
    put("relayUdpEnabled", snapshot.relayUdpEnabled)
    put("relayTcpFallbackEnabled", snapshot.relayTcpFallbackEnabled)
    put("relayFinalmaskType", snapshot.relayFinalmaskType)
    put("relayFinalmaskHeaderHex", snapshot.relayFinalmaskHeaderHex)
    put("relayFinalmaskTrailerHex", snapshot.relayFinalmaskTrailerHex)
    put("relayFinalmaskRandRange", snapshot.relayFinalmaskRandRange)
    put("relayFinalmaskSudokuSeed", snapshot.relayFinalmaskSudokuSeed)
    put("relayFinalmaskFragmentPackets", snapshot.relayFinalmaskFragmentPackets)
    put("relayFinalmaskFragmentMinBytes", snapshot.relayFinalmaskFragmentMinBytes)
    put("relayFinalmaskFragmentMaxBytes", snapshot.relayFinalmaskFragmentMaxBytes)
}

@Suppress("LongMethod")
internal fun JsonObject.readRelaySnapshot(defaults: AppSettingsRelaySnapshot): AppSettingsRelaySnapshot =
    AppSettingsRelaySnapshot(
        relayEnabled = booleanValue("relayEnabled", defaults.relayEnabled),
        relayKind = stringValue("relayKind", defaults.relayKind),
        relayProfileId = stringValue("relayProfileId", defaults.relayProfileId),
        relayOutboundBindIp = stringValue("relayOutboundBindIp", defaults.relayOutboundBindIp),
        relayServer = stringValue("relayServer", defaults.relayServer),
        relayServerPort = intValue("relayServerPort", defaults.relayServerPort),
        relayServerName = stringValue("relayServerName", defaults.relayServerName),
        relayRealityPublicKey = stringValue("relayRealityPublicKey", defaults.relayRealityPublicKey),
        relayRealityShortId = stringValue("relayRealityShortId", defaults.relayRealityShortId),
        relayVlessTransport = stringValue("relayVlessTransport", defaults.relayVlessTransport),
        relayXhttpPath = stringValue("relayXhttpPath", defaults.relayXhttpPath),
        relayXhttpHost = stringValue("relayXhttpHost", defaults.relayXhttpHost),
        relayCloudflareTunnelMode = stringValue("relayCloudflareTunnelMode", defaults.relayCloudflareTunnelMode),
        relayCloudflarePublishLocalOriginUrl =
            stringValue("relayCloudflarePublishLocalOriginUrl", defaults.relayCloudflarePublishLocalOriginUrl),
        relayCloudflareCredentialsRef =
            stringValue("relayCloudflareCredentialsRef", defaults.relayCloudflareCredentialsRef),
        relayChainEntryServer = stringValue("relayChainEntryServer", defaults.relayChainEntryServer),
        relayChainEntryPort = intValue("relayChainEntryPort", defaults.relayChainEntryPort),
        relayChainEntryServerName = stringValue("relayChainEntryServerName", defaults.relayChainEntryServerName),
        relayChainEntryPublicKey = stringValue("relayChainEntryPublicKey", defaults.relayChainEntryPublicKey),
        relayChainEntryShortId = stringValue("relayChainEntryShortId", defaults.relayChainEntryShortId),
        relayChainEntryProfileId = stringValue("relayChainEntryProfileId", defaults.relayChainEntryProfileId),
        relayChainExitServer = stringValue("relayChainExitServer", defaults.relayChainExitServer),
        relayChainExitPort = intValue("relayChainExitPort", defaults.relayChainExitPort),
        relayChainExitServerName = stringValue("relayChainExitServerName", defaults.relayChainExitServerName),
        relayChainExitPublicKey = stringValue("relayChainExitPublicKey", defaults.relayChainExitPublicKey),
        relayChainExitShortId = stringValue("relayChainExitShortId", defaults.relayChainExitShortId),
        relayChainExitProfileId = stringValue("relayChainExitProfileId", defaults.relayChainExitProfileId),
        relayMasqueUrl = stringValue("relayMasqueUrl", defaults.relayMasqueUrl),
        relayMasqueUseHttp2Fallback =
            booleanValue("relayMasqueUseHttp2Fallback", defaults.relayMasqueUseHttp2Fallback),
        relayMasqueCloudflareGeohashEnabled =
            booleanValue("relayMasqueCloudflareGeohashEnabled", defaults.relayMasqueCloudflareGeohashEnabled),
        relayTuicZeroRtt = booleanValue("relayTuicZeroRtt", defaults.relayTuicZeroRtt),
        relayTuicCongestionControl = stringValue("relayTuicCongestionControl", defaults.relayTuicCongestionControl),
        relayShadowTlsInnerProfileId =
            stringValue("relayShadowTlsInnerProfileId", defaults.relayShadowTlsInnerProfileId),
        relayNaivePath = stringValue("relayNaivePath", defaults.relayNaivePath),
        relayAppsScriptScriptIds =
            stringListValue("relayAppsScriptScriptIds", defaults.relayAppsScriptScriptIds),
        relayAppsScriptGoogleIp = stringValue("relayAppsScriptGoogleIp", defaults.relayAppsScriptGoogleIp),
        relayAppsScriptFrontDomain =
            stringValue("relayAppsScriptFrontDomain", defaults.relayAppsScriptFrontDomain),
        relayAppsScriptSniHosts = stringListValue("relayAppsScriptSniHosts", defaults.relayAppsScriptSniHosts),
        relayAppsScriptVerifySsl = booleanValue("relayAppsScriptVerifySsl", defaults.relayAppsScriptVerifySsl),
        relayAppsScriptParallelRelay =
            booleanValue("relayAppsScriptParallelRelay", defaults.relayAppsScriptParallelRelay),
        relayAppsScriptDirectHosts =
            stringListValue("relayAppsScriptDirectHosts", defaults.relayAppsScriptDirectHosts),
        relayLocalSocksHost = stringValue("relayLocalSocksHost", defaults.relayLocalSocksHost),
        relayLocalSocksPort = intValue("relayLocalSocksPort", defaults.relayLocalSocksPort),
        relayUdpEnabled = booleanValue("relayUdpEnabled", defaults.relayUdpEnabled),
        relayTcpFallbackEnabled = booleanValue("relayTcpFallbackEnabled", defaults.relayTcpFallbackEnabled),
        relayFinalmaskType = stringValue("relayFinalmaskType", defaults.relayFinalmaskType),
        relayFinalmaskHeaderHex = stringValue("relayFinalmaskHeaderHex", defaults.relayFinalmaskHeaderHex),
        relayFinalmaskTrailerHex = stringValue("relayFinalmaskTrailerHex", defaults.relayFinalmaskTrailerHex),
        relayFinalmaskRandRange = stringValue("relayFinalmaskRandRange", defaults.relayFinalmaskRandRange),
        relayFinalmaskSudokuSeed = stringValue("relayFinalmaskSudokuSeed", defaults.relayFinalmaskSudokuSeed),
        relayFinalmaskFragmentPackets =
            intValue("relayFinalmaskFragmentPackets", defaults.relayFinalmaskFragmentPackets),
        relayFinalmaskFragmentMinBytes =
            intValue("relayFinalmaskFragmentMinBytes", defaults.relayFinalmaskFragmentMinBytes),
        relayFinalmaskFragmentMaxBytes =
            intValue("relayFinalmaskFragmentMaxBytes", defaults.relayFinalmaskFragmentMaxBytes),
    )
