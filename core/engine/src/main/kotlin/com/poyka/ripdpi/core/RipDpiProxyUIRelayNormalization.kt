package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.DefaultRelayLocalSocksHost
import com.poyka.ripdpi.data.DefaultRelayLocalSocksPort
import com.poyka.ripdpi.data.DefaultRelayProfileId
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindOff
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.normalizeRelayCongestionControl
import com.poyka.ripdpi.data.normalizeRelayKind
import com.poyka.ripdpi.data.normalizeRelayStringList
import com.poyka.ripdpi.data.normalizeRelayVlessTransport

internal fun normalizeRelayConfig(config: RipDpiRelayConfig): RipDpiRelayConfig {
    val normalizedKind = normalizeRelayKind(config.kind)
    return config.copy(
        enabled = config.enabled && normalizedKind != RelayKindOff,
        kind = normalizedKind,
        profileId = config.profileId.trim().ifBlank { DefaultRelayProfileId },
        outboundBindIp = config.outboundBindIp.trim(),
        server = config.server.trim(),
        serverPort = config.serverPort.takeIf { it in 1..MaxValidPortNumber } ?: 443,
        serverName = config.serverName.trim(),
        realityPublicKey = config.realityPublicKey.trim(),
        realityShortId = config.realityShortId.trim(),
        vlessFlow = config.vlessFlow.trim().ifBlank { com.poyka.ripdpi.data.RelayVlessFlowVision },
        vlessTransport = normalizeRelayVlessTransport(config.vlessTransport, normalizedKind),
        xhttpPath = config.xhttpPath.trim(),
        xhttpHost = config.xhttpHost.trim(),
        chainEntryServer = config.chainEntryServer.trim(),
        chainEntryPort = config.chainEntryPort.takeIf { it in 1..MaxValidPortNumber } ?: 443,
        chainEntryServerName = config.chainEntryServerName.trim(),
        chainEntryPublicKey = config.chainEntryPublicKey.trim(),
        chainEntryShortId = config.chainEntryShortId.trim(),
        chainEntryProfileId = config.chainEntryProfileId.trim(),
        chainExitServer = config.chainExitServer.trim(),
        chainExitPort = config.chainExitPort.takeIf { it in 1..MaxValidPortNumber } ?: 443,
        chainExitServerName = config.chainExitServerName.trim(),
        chainExitPublicKey = config.chainExitPublicKey.trim(),
        chainExitShortId = config.chainExitShortId.trim(),
        chainExitProfileId = config.chainExitProfileId.trim(),
        chainMiddleProfileIds = normalizeRelayStringList(config.chainMiddleProfileIds),
        masqueUrl = config.masqueUrl.trim(),
        tuicCongestionControl = normalizeRelayCongestionControl(config.tuicCongestionControl),
        shadowTlsInnerProfileId = config.shadowTlsInnerProfileId.trim(),
        naivePath = config.naivePath.trim(),
        appsScriptScriptIds = normalizeRelayStringList(config.appsScriptScriptIds),
        appsScriptGoogleIp = config.appsScriptGoogleIp.trim(),
        appsScriptFrontDomain = config.appsScriptFrontDomain.trim(),
        appsScriptSniHosts = normalizeRelayStringList(config.appsScriptSniHosts),
        appsScriptVerifySsl = config.appsScriptVerifySsl,
        appsScriptParallelRelay = config.appsScriptParallelRelay,
        appsScriptDirectHosts = normalizeRelayStringList(config.appsScriptDirectHosts),
        localSocksHost = config.localSocksHost.ifBlank { DefaultRelayLocalSocksHost },
        localSocksPort = config.localSocksPort.takeIf { it in 1..MaxValidPortNumber } ?: DefaultRelayLocalSocksPort,
        udpEnabled =
            when (normalizedKind) {
                RelayKindHysteria2, RelayKindMasque, RelayKindTuicV5 -> config.udpEnabled
                else -> false
            },
        tcpFallbackEnabled = normalizedKind != RelayKindShadowTlsV3 && config.tcpFallbackEnabled,
    )
}
