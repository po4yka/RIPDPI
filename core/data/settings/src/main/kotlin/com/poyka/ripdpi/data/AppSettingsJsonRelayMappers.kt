package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

private const val DefaultHttpsPort = 443

@Suppress("LongMethod")
internal fun AppSettingsSnapshot.withRelaySnapshot(relaySection: RelaySettingsSection): AppSettingsSnapshot =
    copy(
        relay =
            AppSettingsRelaySnapshot(
                relayEnabled = relaySection.relayEnabled,
                relayKind = normalizeRelayKind(relaySection.relayKind),
                relayProfileId = relaySection.relayProfileId.ifBlank { DefaultRelayProfileId },
                relayOutboundBindIp = relaySection.relayOutboundBindIp,
                relayServer = relaySection.relayServer,
                relayServerPort = relaySection.relayServerPort.takeIf { it > 0 } ?: DefaultHttpsPort,
                relayServerName = relaySection.relayServerName,
                relayRealityPublicKey = relaySection.relayRealityPublicKey,
                relayRealityShortId = relaySection.relayRealityShortId,
                relayVlessTransport =
                    normalizeRelayVlessTransport(relaySection.relayVlessTransport, relaySection.relayKind),
                relayXhttpPath = relaySection.relayXhttpPath,
                relayXhttpHost = relaySection.relayXhttpHost,
                relayXhttpMode = normalizeRelayXhttpMode(relaySection.relayXhttpMode),
                relayCloudflareTunnelMode = normalizeRelayCloudflareTunnelMode(relaySection.relayCloudflareTunnelMode),
                relayCloudflarePublishLocalOriginUrl = relaySection.relayCloudflarePublishLocalOriginUrl,
                relayCloudflareCredentialsRef = relaySection.relayCloudflareCredentialsRef,
                relayChainEntryServer = relaySection.relayChainEntryServer,
                relayChainEntryPort = relaySection.relayChainEntryPort.takeIf { it > 0 } ?: DefaultHttpsPort,
                relayChainEntryServerName = relaySection.relayChainEntryServerName,
                relayChainEntryPublicKey = relaySection.relayChainEntryPublicKey,
                relayChainEntryShortId = relaySection.relayChainEntryShortId,
                relayChainEntryProfileId = relaySection.relayChainEntryProfileId,
                relayChainExitServer = relaySection.relayChainExitServer,
                relayChainExitPort = relaySection.relayChainExitPort.takeIf { it > 0 } ?: DefaultHttpsPort,
                relayChainExitServerName = relaySection.relayChainExitServerName,
                relayChainExitPublicKey = relaySection.relayChainExitPublicKey,
                relayChainExitShortId = relaySection.relayChainExitShortId,
                relayChainExitProfileId = relaySection.relayChainExitProfileId,
                relayChainMiddleProfileIds = normalizeRelayStringList(relaySection.relayChainMiddleProfileIds),
                relayMasqueUrl = relaySection.relayMasqueUrl,
                relayMasqueUseHttp2Fallback = relaySection.relayMasqueUseHttp2Fallback,
                relayMasqueCloudflareGeohashEnabled = relaySection.relayMasqueCloudflareGeohashEnabled,
                relayTuicZeroRtt = relaySection.relayTuicZeroRtt,
                relayTuicCongestionControl = normalizeRelayCongestionControl(relaySection.relayTuicCongestionControl),
                relayShadowTlsInnerProfileId = relaySection.relayShadowtlsInnerProfileId,
                relayNaivePath = relaySection.relayNaivePath,
                relayAppsScriptScriptIds = normalizeRelayStringList(relaySection.relayAppsScriptScriptIds),
                relayAppsScriptGoogleIp = relaySection.relayAppsScriptGoogleIp.trim(),
                relayAppsScriptFrontDomain = relaySection.relayAppsScriptFrontDomain.trim(),
                relayAppsScriptSniHosts = normalizeRelayStringList(relaySection.relayAppsScriptSniHosts),
                relayAppsScriptVerifySsl = relaySection.relayAppsScriptVerifySsl,
                relayAppsScriptParallelRelay = relaySection.relayAppsScriptParallelRelay,
                relayAppsScriptDirectHosts = normalizeRelayStringList(relaySection.relayAppsScriptDirectHosts),
                relayLocalSocksHost = relaySection.relayLocalSocksHost.ifBlank { DefaultRelayLocalSocksHost },
                relayLocalSocksPort = relaySection.relayLocalSocksPort.takeIf { it > 0 } ?: DefaultRelayLocalSocksPort,
                relayUdpEnabled = relaySection.relayUdpEnabled,
                relayTcpFallbackEnabled = relaySection.relayTcpFallbackEnabled,
                relayDnsOverTunnelEnabled = relaySection.relayDnsOverTunnelEnabled,
                relayFinalmaskType = normalizeRelayFinalmaskType(relaySection.relayFinalmaskType),
                relayFinalmaskHeaderHex = relaySection.relayFinalmaskHeaderHex,
                relayFinalmaskTrailerHex = relaySection.relayFinalmaskTrailerHex,
                relayFinalmaskRandRange = relaySection.relayFinalmaskRandRange,
                relayFinalmaskSudokuSeed = relaySection.relayFinalmaskSudokuSeed,
                relayFinalmaskFragmentPackets = relaySection.relayFinalmaskFragmentPackets,
                relayFinalmaskFragmentMinBytes = relaySection.relayFinalmaskFragmentMinBytes,
                relayFinalmaskFragmentMaxBytes = relaySection.relayFinalmaskFragmentMaxBytes,
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
        .setRelayXhttpMode(normalizeRelayXhttpMode(snapshot.relayXhttpMode))
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
        .clearRelayChainMiddleProfileIds()
        .addAllRelayChainMiddleProfileIds(normalizeRelayStringList(snapshot.relayChainMiddleProfileIds))
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
        .setRelayDnsOverTunnelEnabled(snapshot.relayDnsOverTunnelEnabled)
        .setRelayFinalmaskType(normalizeRelayFinalmaskType(snapshot.relayFinalmaskType))
        .setRelayFinalmaskHeaderHex(snapshot.relayFinalmaskHeaderHex)
        .setRelayFinalmaskTrailerHex(snapshot.relayFinalmaskTrailerHex)
        .setRelayFinalmaskRandRange(snapshot.relayFinalmaskRandRange)
        .setRelayFinalmaskSudokuSeed(snapshot.relayFinalmaskSudokuSeed)
        .setRelayFinalmaskFragmentPackets(snapshot.relayFinalmaskFragmentPackets)
        .setRelayFinalmaskFragmentMinBytes(snapshot.relayFinalmaskFragmentMinBytes)
        .setRelayFinalmaskFragmentMaxBytes(snapshot.relayFinalmaskFragmentMaxBytes)
