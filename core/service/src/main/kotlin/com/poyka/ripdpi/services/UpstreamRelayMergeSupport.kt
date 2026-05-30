package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiRelayConfig
import com.poyka.ripdpi.data.RelayProfileRecord

internal fun mergeRelayConfig(
    config: RipDpiRelayConfig,
    profile: RelayProfileRecord?,
): RipDpiRelayConfig {
    if (profile == null) return config
    return config.copy(
        kind = profile.kind.ifBlank { config.kind },
        outboundBindIp = profile.outboundBindIp.ifBlank { config.outboundBindIp },
        server = profile.server.ifBlank { config.server },
        serverPort = profile.serverPort,
        serverName = profile.serverName.ifBlank { config.serverName },
        realityPublicKey = profile.realityPublicKey.ifBlank { config.realityPublicKey },
        realityShortId = profile.realityShortId.ifBlank { config.realityShortId },
        vlessTransport = profile.vlessTransport.ifBlank { config.vlessTransport },
        xhttpPath = profile.xhttpPath.ifBlank { config.xhttpPath },
        xhttpHost = profile.xhttpHost.ifBlank { config.xhttpHost },
        cloudflareTunnelMode = profile.cloudflareTunnelMode.ifBlank { config.cloudflareTunnelMode },
        cloudflarePublishLocalOriginUrl =
            profile.cloudflarePublishLocalOriginUrl.ifBlank { config.cloudflarePublishLocalOriginUrl },
        cloudflareCredentialsRef = profile.cloudflareCredentialsRef.ifBlank { config.cloudflareCredentialsRef },
        chainEntryServer = profile.chainEntryServer.ifBlank { config.chainEntryServer },
        chainEntryPort = profile.chainEntryPort,
        chainEntryServerName = profile.chainEntryServerName.ifBlank { config.chainEntryServerName },
        chainEntryPublicKey = profile.chainEntryPublicKey.ifBlank { config.chainEntryPublicKey },
        chainEntryShortId = profile.chainEntryShortId.ifBlank { config.chainEntryShortId },
        chainEntryProfileId = profile.chainEntryProfileId.ifBlank { config.chainEntryProfileId },
        chainExitServer = profile.chainExitServer.ifBlank { config.chainExitServer },
        chainExitPort = profile.chainExitPort,
        chainExitServerName = profile.chainExitServerName.ifBlank { config.chainExitServerName },
        chainExitPublicKey = profile.chainExitPublicKey.ifBlank { config.chainExitPublicKey },
        chainExitShortId = profile.chainExitShortId.ifBlank { config.chainExitShortId },
        chainExitProfileId = profile.chainExitProfileId.ifBlank { config.chainExitProfileId },
        chainMiddleProfileIds =
            profile.chainMiddleProfileIds.takeIf {
                it.isNotEmpty()
            } ?: config.chainMiddleProfileIds,
        masqueUrl = profile.masqueUrl.ifBlank { config.masqueUrl },
        masqueUseHttp2Fallback = profile.masqueUseHttp2Fallback,
        masqueCloudflareGeohashEnabled = profile.masqueCloudflareGeohashEnabled,
        tuicZeroRtt = profile.tuicZeroRtt,
        tuicCongestionControl = profile.tuicCongestionControl,
        shadowTlsInnerProfileId = profile.shadowTlsInnerProfileId.ifBlank { config.shadowTlsInnerProfileId },
        naivePath = profile.naivePath.ifBlank { config.naivePath },
        appsScriptScriptIds = profile.appsScriptScriptIds.takeIf { it.isNotEmpty() } ?: config.appsScriptScriptIds,
        appsScriptGoogleIp = profile.appsScriptGoogleIp.ifBlank { config.appsScriptGoogleIp },
        appsScriptFrontDomain = profile.appsScriptFrontDomain.ifBlank { config.appsScriptFrontDomain },
        appsScriptSniHosts = profile.appsScriptSniHosts.takeIf { it.isNotEmpty() } ?: config.appsScriptSniHosts,
        appsScriptVerifySsl = profile.appsScriptVerifySsl,
        appsScriptParallelRelay = profile.appsScriptParallelRelay,
        appsScriptDirectHosts =
            profile.appsScriptDirectHosts.takeIf { it.isNotEmpty() } ?: config.appsScriptDirectHosts,
        ptBridgeLine = profile.ptBridgeLine.ifBlank { config.ptBridgeLine },
        ptWebTunnelUrl = profile.ptWebTunnelUrl.ifBlank { config.ptWebTunnelUrl },
        ptSnowflakeBrokerUrl = profile.ptSnowflakeBrokerUrl.ifBlank { config.ptSnowflakeBrokerUrl },
        ptSnowflakeFrontDomain = profile.ptSnowflakeFrontDomain.ifBlank { config.ptSnowflakeFrontDomain },
        localSocksHost = profile.localSocksHost.ifBlank { config.localSocksHost },
        localSocksPort = profile.localSocksPort,
        udpEnabled = profile.udpEnabled,
        tcpFallbackEnabled = profile.tcpFallbackEnabled,
        finalmask =
            config.finalmask.copy(
                type = profile.finalmaskType.ifBlank { config.finalmask.type },
                headerHex = profile.finalmaskHeaderHex.ifBlank { config.finalmask.headerHex },
                trailerHex = profile.finalmaskTrailerHex.ifBlank { config.finalmask.trailerHex },
                randRange = profile.finalmaskRandRange.ifBlank { config.finalmask.randRange },
                sudokuSeed = profile.finalmaskSudokuSeed.ifBlank { config.finalmask.sudokuSeed },
                fragmentPackets =
                    profile.finalmaskFragmentPackets.takeIf { it > 0 } ?: config.finalmask.fragmentPackets,
                fragmentMinBytes =
                    profile.finalmaskFragmentMinBytes.takeIf { it > 0 } ?: config.finalmask.fragmentMinBytes,
                fragmentMaxBytes =
                    profile.finalmaskFragmentMaxBytes.takeIf { it > 0 } ?: config.finalmask.fragmentMaxBytes,
            ),
    )
}
