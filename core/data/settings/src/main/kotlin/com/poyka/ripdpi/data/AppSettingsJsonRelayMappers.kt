package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

private const val DefaultHttpsPort = 443

@Suppress("LongMethod")
internal fun AppSettingsSnapshot.withRelaySnapshot(settings: AppSettings): AppSettingsSnapshot =
    copy(
        relay =
            AppSettingsRelaySnapshot(
                relayEnabled = settings.relayEnabled,
                relayKind = normalizeRelayKind(settings.relayKind),
                relayProfileId = settings.relayProfileId.ifBlank { DefaultRelayProfileId },
                relayOutboundBindIp = settings.relayOutboundBindIp,
                relayServer = settings.relayServer,
                relayServerPort = settings.relayServerPort.takeIf { it > 0 } ?: DefaultHttpsPort,
                relayServerName = settings.relayServerName,
                relayRealityPublicKey = settings.relayRealityPublicKey,
                relayRealityShortId = settings.relayRealityShortId,
                relayVlessTransport = normalizeRelayVlessTransport(settings.relayVlessTransport, settings.relayKind),
                relayXhttpPath = settings.relayXhttpPath,
                relayXhttpHost = settings.relayXhttpHost,
                relayCloudflareTunnelMode = normalizeRelayCloudflareTunnelMode(settings.relayCloudflareTunnelMode),
                relayCloudflarePublishLocalOriginUrl = settings.relayCloudflarePublishLocalOriginUrl,
                relayCloudflareCredentialsRef = settings.relayCloudflareCredentialsRef,
                relayChainEntryServer = settings.relayChainEntryServer,
                relayChainEntryPort = settings.relayChainEntryPort.takeIf { it > 0 } ?: DefaultHttpsPort,
                relayChainEntryServerName = settings.relayChainEntryServerName,
                relayChainEntryPublicKey = settings.relayChainEntryPublicKey,
                relayChainEntryShortId = settings.relayChainEntryShortId,
                relayChainEntryProfileId = settings.relayChainEntryProfileId,
                relayChainExitServer = settings.relayChainExitServer,
                relayChainExitPort = settings.relayChainExitPort.takeIf { it > 0 } ?: DefaultHttpsPort,
                relayChainExitServerName = settings.relayChainExitServerName,
                relayChainExitPublicKey = settings.relayChainExitPublicKey,
                relayChainExitShortId = settings.relayChainExitShortId,
                relayChainExitProfileId = settings.relayChainExitProfileId,
                relayMasqueUrl = settings.relayMasqueUrl,
                relayMasqueUseHttp2Fallback = settings.relayMasqueUseHttp2Fallback,
                relayMasqueCloudflareGeohashEnabled = settings.relayMasqueCloudflareGeohashEnabled,
                relayTuicZeroRtt = settings.relayTuicZeroRtt,
                relayTuicCongestionControl = normalizeRelayCongestionControl(settings.relayTuicCongestionControl),
                relayShadowTlsInnerProfileId = settings.relayShadowtlsInnerProfileId,
                relayNaivePath = settings.relayNaivePath,
                relayAppsScriptScriptIds = normalizeRelayStringList(settings.relayAppsScriptScriptIdsList),
                relayAppsScriptGoogleIp = settings.relayAppsScriptGoogleIp.trim(),
                relayAppsScriptFrontDomain = settings.relayAppsScriptFrontDomain.trim(),
                relayAppsScriptSniHosts = normalizeRelayStringList(settings.relayAppsScriptSniHostsList),
                relayAppsScriptVerifySsl = settings.relayAppsScriptVerifySsl,
                relayAppsScriptParallelRelay = settings.relayAppsScriptParallelRelay,
                relayAppsScriptDirectHosts = normalizeRelayStringList(settings.relayAppsScriptDirectHostsList),
                relayLocalSocksHost = settings.relayLocalSocksHost.ifBlank { DefaultRelayLocalSocksHost },
                relayLocalSocksPort = settings.relayLocalSocksPort.takeIf { it > 0 } ?: DefaultRelayLocalSocksPort,
                relayUdpEnabled = settings.relayUdpEnabled,
                relayTcpFallbackEnabled = settings.relayTcpFallbackEnabled,
                relayFinalmaskType = normalizeRelayFinalmaskType(settings.relayFinalmaskType),
                relayFinalmaskHeaderHex = settings.relayFinalmaskHeaderHex,
                relayFinalmaskTrailerHex = settings.relayFinalmaskTrailerHex,
                relayFinalmaskRandRange = settings.relayFinalmaskRandRange,
                relayFinalmaskSudokuSeed = settings.relayFinalmaskSudokuSeed,
                relayFinalmaskFragmentPackets = settings.relayFinalmaskFragmentPackets,
                relayFinalmaskFragmentMinBytes = settings.relayFinalmaskFragmentMinBytes,
                relayFinalmaskFragmentMaxBytes = settings.relayFinalmaskFragmentMaxBytes,
            ),
    )

@Suppress("LongMethod")
internal fun AppSettings.Builder.applyRelaySnapshot(snapshot: AppSettingsSnapshot): AppSettings.Builder =
    applyRelaySnapshot(snapshot.relay)

@Suppress("LongMethod")
private fun AppSettings.Builder.applyRelaySnapshot(snapshot: AppSettingsRelaySnapshot): AppSettings.Builder =
    setRelayEnabled(snapshot.relayEnabled)
        .setRelayKind(normalizeRelayKind(snapshot.relayKind))
        .setRelayProfileId(snapshot.relayProfileId.ifBlank { DefaultRelayProfileId })
        .setRelayOutboundBindIp(snapshot.relayOutboundBindIp)
        .setRelayServer(snapshot.relayServer)
        .setRelayServerPort(snapshot.relayServerPort.takeIf { it > 0 } ?: DefaultHttpsPort)
        .setRelayServerName(snapshot.relayServerName)
        .setRelayRealityPublicKey(snapshot.relayRealityPublicKey)
        .setRelayRealityShortId(snapshot.relayRealityShortId)
        .setRelayVlessTransport(normalizeRelayVlessTransport(snapshot.relayVlessTransport, snapshot.relayKind))
        .setRelayXhttpPath(snapshot.relayXhttpPath)
        .setRelayXhttpHost(snapshot.relayXhttpHost)
        .setRelayCloudflareTunnelMode(normalizeRelayCloudflareTunnelMode(snapshot.relayCloudflareTunnelMode))
        .setRelayCloudflarePublishLocalOriginUrl(snapshot.relayCloudflarePublishLocalOriginUrl)
        .setRelayCloudflareCredentialsRef(snapshot.relayCloudflareCredentialsRef)
        .setRelayChainEntryServer(snapshot.relayChainEntryServer)
        .setRelayChainEntryPort(snapshot.relayChainEntryPort.takeIf { it > 0 } ?: DefaultHttpsPort)
        .setRelayChainEntryServerName(snapshot.relayChainEntryServerName)
        .setRelayChainEntryPublicKey(snapshot.relayChainEntryPublicKey)
        .setRelayChainEntryShortId(snapshot.relayChainEntryShortId)
        .setRelayChainEntryProfileId(snapshot.relayChainEntryProfileId)
        .setRelayChainExitServer(snapshot.relayChainExitServer)
        .setRelayChainExitPort(snapshot.relayChainExitPort.takeIf { it > 0 } ?: DefaultHttpsPort)
        .setRelayChainExitServerName(snapshot.relayChainExitServerName)
        .setRelayChainExitPublicKey(snapshot.relayChainExitPublicKey)
        .setRelayChainExitShortId(snapshot.relayChainExitShortId)
        .setRelayChainExitProfileId(snapshot.relayChainExitProfileId)
        .setRelayMasqueUrl(snapshot.relayMasqueUrl)
        .setRelayMasqueUseHttp2Fallback(snapshot.relayMasqueUseHttp2Fallback)
        .setRelayMasqueCloudflareGeohashEnabled(snapshot.relayMasqueCloudflareGeohashEnabled)
        .setRelayTuicZeroRtt(snapshot.relayTuicZeroRtt)
        .setRelayTuicCongestionControl(normalizeRelayCongestionControl(snapshot.relayTuicCongestionControl))
        .setRelayShadowtlsInnerProfileId(snapshot.relayShadowTlsInnerProfileId)
        .setRelayNaivePath(snapshot.relayNaivePath)
        .clearRelayAppsScriptScriptIds()
        .addAllRelayAppsScriptScriptIds(normalizeRelayStringList(snapshot.relayAppsScriptScriptIds))
        .setRelayAppsScriptGoogleIp(snapshot.relayAppsScriptGoogleIp.trim())
        .setRelayAppsScriptFrontDomain(snapshot.relayAppsScriptFrontDomain.trim())
        .clearRelayAppsScriptSniHosts()
        .addAllRelayAppsScriptSniHosts(normalizeRelayStringList(snapshot.relayAppsScriptSniHosts))
        .setRelayAppsScriptVerifySsl(snapshot.relayAppsScriptVerifySsl)
        .setRelayAppsScriptParallelRelay(snapshot.relayAppsScriptParallelRelay)
        .clearRelayAppsScriptDirectHosts()
        .addAllRelayAppsScriptDirectHosts(normalizeRelayStringList(snapshot.relayAppsScriptDirectHosts))
        .setRelayLocalSocksHost(snapshot.relayLocalSocksHost.ifBlank { DefaultRelayLocalSocksHost })
        .setRelayLocalSocksPort(snapshot.relayLocalSocksPort.takeIf { it > 0 } ?: DefaultRelayLocalSocksPort)
        .setRelayUdpEnabled(snapshot.relayUdpEnabled)
        .setRelayTcpFallbackEnabled(snapshot.relayTcpFallbackEnabled)
        .setRelayFinalmaskType(normalizeRelayFinalmaskType(snapshot.relayFinalmaskType))
        .setRelayFinalmaskHeaderHex(snapshot.relayFinalmaskHeaderHex)
        .setRelayFinalmaskTrailerHex(snapshot.relayFinalmaskTrailerHex)
        .setRelayFinalmaskRandRange(snapshot.relayFinalmaskRandRange)
        .setRelayFinalmaskSudokuSeed(snapshot.relayFinalmaskSudokuSeed)
        .setRelayFinalmaskFragmentPackets(snapshot.relayFinalmaskFragmentPackets)
        .setRelayFinalmaskFragmentMinBytes(snapshot.relayFinalmaskFragmentMinBytes)
        .setRelayFinalmaskFragmentMaxBytes(snapshot.relayFinalmaskFragmentMaxBytes)
