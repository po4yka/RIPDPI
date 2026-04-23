package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.DefaultEntropyPaddingMax
import com.poyka.ripdpi.data.DefaultEntropyPaddingTargetPermil
import com.poyka.ripdpi.data.DefaultEvolutionEpsilon
import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.DefaultRelayLocalSocksHost
import com.poyka.ripdpi.data.DefaultRelayLocalSocksPort
import com.poyka.ripdpi.data.DefaultShannonEntropyTargetPermil
import com.poyka.ripdpi.data.DefaultSplitMarker
import com.poyka.ripdpi.data.DefaultTlsRecordMarker
import com.poyka.ripdpi.data.DefaultWarpLocalSocksPort
import com.poyka.ripdpi.data.DefaultWarpManualEndpointPort
import com.poyka.ripdpi.data.FakePayloadProfileCompatDefault
import com.poyka.ripdpi.data.QuicFakeProfileDisabled
import com.poyka.ripdpi.data.QuicInitialModeRouteAndCache
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindOff
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.StrategyLaneFamilies
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.TlsFingerprintProfileChromeStable
import com.poyka.ripdpi.data.WarpAmneziaPresetOff
import com.poyka.ripdpi.data.deriveStrategyLaneFamilies
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlDelta
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlMax
import com.poyka.ripdpi.data.effectiveAdaptiveFakeTtlMin
import com.poyka.ripdpi.data.effectiveFakeOffsetMarker
import com.poyka.ripdpi.data.effectiveFakeTlsSniMode
import com.poyka.ripdpi.data.effectiveGroupActivationFilter
import com.poyka.ripdpi.data.effectiveHttpFakeProfile
import com.poyka.ripdpi.data.effectiveQuicFakeHost
import com.poyka.ripdpi.data.effectiveQuicFakeProfile
import com.poyka.ripdpi.data.effectiveQuicInitialMode
import com.poyka.ripdpi.data.effectiveQuicSupportV1
import com.poyka.ripdpi.data.effectiveQuicSupportV2
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveTlsFakeProfile
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.effectiveUdpFakeProfile
import com.poyka.ripdpi.data.formatChainSummary
import com.poyka.ripdpi.data.isTlsPrelude
import com.poyka.ripdpi.data.normalizeActivationFilter
import com.poyka.ripdpi.data.normalizeAdaptiveFakeTtlDelta
import com.poyka.ripdpi.data.normalizeAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.normalizeAdaptiveFakeTtlMax
import com.poyka.ripdpi.data.normalizeAdaptiveFakeTtlMin
import com.poyka.ripdpi.data.normalizeAdaptiveFallbackCachePrefixV4
import com.poyka.ripdpi.data.normalizeAdaptiveFallbackCacheTtlSeconds
import com.poyka.ripdpi.data.normalizeEntropyMode
import com.poyka.ripdpi.data.normalizeFakeTlsSniMode
import com.poyka.ripdpi.data.normalizeHostAutolearnMaxHosts
import com.poyka.ripdpi.data.normalizeHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.normalizeHttpFakeProfile
import com.poyka.ripdpi.data.normalizeIpIdMode
import com.poyka.ripdpi.data.normalizeOffsetExpression
import com.poyka.ripdpi.data.normalizeQuicFakeHost
import com.poyka.ripdpi.data.normalizeQuicFakeProfile
import com.poyka.ripdpi.data.normalizeQuicInitialMode
import com.poyka.ripdpi.data.normalizeRelayCongestionControl
import com.poyka.ripdpi.data.normalizeRelayKind
import com.poyka.ripdpi.data.normalizeRelayStringList
import com.poyka.ripdpi.data.normalizeRelayVlessTransport
import com.poyka.ripdpi.data.normalizeTcpChainStepModel
import com.poyka.ripdpi.data.normalizeTlsFakeProfile
import com.poyka.ripdpi.data.normalizeTlsFingerprintProfile
import com.poyka.ripdpi.data.normalizeUdpChainStepModel
import com.poyka.ripdpi.data.normalizeUdpFakeProfile
import com.poyka.ripdpi.data.setGroupActivationFilterCompat
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.data.toAdaptiveFallbackSettingsModel
import com.poyka.ripdpi.data.toRelaySettingsModel
import com.poyka.ripdpi.data.toWarpSettingsModel
import com.poyka.ripdpi.proto.AppSettings

private const val WsizeScaleMin = -1
private const val WsizeScaleMax = 14

internal fun normalizeListenConfig(config: RipDpiListenConfig): RipDpiListenConfig =
    config.copy(ip = config.ip.ifBlank { "127.0.0.1" })

internal fun normalizeChainConfig(config: RipDpiChainConfig): RipDpiChainConfig =
    config.copy(
        groupActivationFilter = normalizeActivationFilter(config.groupActivationFilter),
        tcpSteps = config.tcpSteps.map(::normalizeTcpChainStep),
        udpSteps = config.udpSteps.map(::normalizeUdpChainStepModel),
        anyProtocol = config.anyProtocol,
    )

internal fun normalizeFakePacketConfig(config: RipDpiFakePacketConfig): RipDpiFakePacketConfig {
    val normalizedAdaptiveFakeTtlMin = normalizeAdaptiveFakeTtlMin(config.adaptiveFakeTtlMin)
    return config.copy(
        adaptiveFakeTtlDelta = normalizeAdaptiveFakeTtlDelta(config.adaptiveFakeTtlDelta),
        adaptiveFakeTtlMin = normalizedAdaptiveFakeTtlMin,
        adaptiveFakeTtlMax = normalizeAdaptiveFakeTtlMax(config.adaptiveFakeTtlMax, normalizedAdaptiveFakeTtlMin),
        adaptiveFakeTtlFallback =
            normalizeAdaptiveFakeTtlFallback(
                config.adaptiveFakeTtlFallback,
                config.fakeTtl.takeIf { it > 0 } ?: DefaultAdaptiveFakeTtlFallback,
            ),
        fakeSni = config.fakeSni.ifBlank { DefaultFakeSni },
        httpFakeProfile = normalizeHttpFakeProfile(config.httpFakeProfile.ifBlank { FakePayloadProfileCompatDefault }),
        fakeTlsSource =
            com.poyka.ripdpi.data
                .normalizeFakeTlsSource(config.fakeTlsSource),
        fakeTlsSecondaryProfile =
            config.fakeTlsSecondaryProfile
                .trim()
                .takeIf(String::isNotEmpty)
                ?.let(::normalizeTlsFakeProfile)
                .orEmpty(),
        fakeTlsSniMode = normalizeFakeTlsSniMode(config.fakeTlsSniMode),
        tlsFakeProfile = normalizeTlsFakeProfile(config.tlsFakeProfile.ifBlank { FakePayloadProfileCompatDefault }),
        udpFakeProfile = normalizeUdpFakeProfile(config.udpFakeProfile.ifBlank { FakePayloadProfileCompatDefault }),
        fakeOffsetMarker = normalizeOffsetExpression(config.fakeOffsetMarker, DefaultFakeOffsetMarker),
        windowClamp = config.windowClamp?.takeIf { it > 0 },
        wsizeWindow = config.wsizeWindow?.takeIf { it > 0 },
        wsizeScale = config.wsizeScale?.takeIf { it in WsizeScaleMin..WsizeScaleMax },
        stripTimestamps = config.stripTimestamps,
        ipIdMode = normalizeIpIdMode(config.ipIdMode),
        quicBindLowPort = config.quicBindLowPort,
        quicMigrateAfterHandshake = config.quicMigrateAfterHandshake,
        entropyMode = normalizeEntropyMode(config.entropyMode),
        entropyPaddingTargetPermil =
            config.entropyPaddingTargetPermil.takeIf { it > 0 } ?: DefaultEntropyPaddingTargetPermil,
        entropyPaddingMax = config.entropyPaddingMax.takeIf { it > 0 } ?: DefaultEntropyPaddingMax,
        shannonEntropyTargetPermil =
            config.shannonEntropyTargetPermil.takeIf { it > 0 } ?: DefaultShannonEntropyTargetPermil,
        tlsFingerprintProfile =
            normalizeTlsFingerprintProfile(
                config.tlsFingerprintProfile.ifBlank {
                    TlsFingerprintProfileChromeStable
                },
            ),
    )
}

internal fun normalizeQuicConfig(config: RipDpiQuicConfig): RipDpiQuicConfig =
    config.copy(
        initialMode = normalizeQuicInitialMode(config.initialMode.ifBlank { QuicInitialModeRouteAndCache }),
        fakeProfile = normalizeQuicFakeProfile(config.fakeProfile.ifBlank { QuicFakeProfileDisabled }),
        fakeHost = normalizeQuicFakeHost(config.fakeHost),
    )

internal fun normalizeAdaptiveFallbackConfig(config: RipDpiAdaptiveFallbackConfig): RipDpiAdaptiveFallbackConfig =
    config.copy(
        cacheTtlSeconds = normalizeAdaptiveFallbackCacheTtlSeconds(config.cacheTtlSeconds),
        cachePrefixV4 = normalizeAdaptiveFallbackCachePrefixV4(config.cachePrefixV4),
        strategyEvolution = config.strategyEvolution,
        evolutionEpsilon =
            config.evolutionEpsilon
                .takeIf { !it.isNaN() }
                ?.coerceIn(0.0, 1.0)
                ?: DefaultEvolutionEpsilon,
    )

internal fun normalizeHostsConfig(config: RipDpiHostsConfig): RipDpiHostsConfig {
    val normalizedEntries = config.entries?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedMode = if (normalizedEntries == null) RipDpiHostsConfig.Mode.Disable else config.mode
    return RipDpiHostsConfig(
        mode = normalizedMode,
        entries = normalizedEntries.takeUnless { normalizedMode == RipDpiHostsConfig.Mode.Disable },
    )
}

internal fun normalizeRelayConfig(config: RipDpiRelayConfig): RipDpiRelayConfig {
    val normalizedKind = normalizeRelayKind(config.kind)
    return config.copy(
        enabled = config.enabled && normalizedKind != RelayKindOff,
        kind = normalizedKind,
        profileId = config.profileId.trim().ifBlank { com.poyka.ripdpi.data.DefaultRelayProfileId },
        outboundBindIp = config.outboundBindIp.trim(),
        server = config.server.trim(),
        serverPort = config.serverPort.takeIf { it in 1..MaxValidPortNumber } ?: 443,
        serverName = config.serverName.trim(),
        realityPublicKey = config.realityPublicKey.trim(),
        realityShortId = config.realityShortId.trim(),
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

internal fun normalizeWarpConfig(config: RipDpiWarpConfig): RipDpiWarpConfig =
    config.copy(
        routeMode =
            com.poyka.ripdpi.data
                .normalizeWarpRouteMode(config.routeMode),
        routeHosts = config.routeHosts.trim(),
        endpointSelectionMode =
            com.poyka.ripdpi.data
                .normalizeWarpEndpointSelectionMode(config.endpointSelectionMode),
        manualEndpoint =
            config.manualEndpoint.copy(
                host = config.manualEndpoint.host.trim(),
                ipv4 = config.manualEndpoint.ipv4.trim(),
                ipv6 = config.manualEndpoint.ipv6.trim(),
                port =
                    config.manualEndpoint.port.takeIf { it in 1..MaxValidPortNumber }
                        ?: DefaultWarpManualEndpointPort,
            ),
        scannerParallelism = config.scannerParallelism.coerceAtLeast(1),
        scannerMaxRttMs = config.scannerMaxRttMs.coerceAtLeast(1),
        amneziaPreset = config.amneziaPreset.trim().ifBlank { WarpAmneziaPresetOff },
        localSocksHost = config.localSocksHost.ifBlank { "127.0.0.1" },
        localSocksPort = config.localSocksPort.takeIf { it in 1..MaxValidPortNumber } ?: DefaultWarpLocalSocksPort,
    )

internal fun normalizeHostAutolearnConfig(config: RipDpiHostAutolearnConfig): RipDpiHostAutolearnConfig =
    config.copy(
        penaltyTtlHours = normalizeHostAutolearnPenaltyTtlHours(config.penaltyTtlHours),
        maxHosts = normalizeHostAutolearnMaxHosts(config.maxHosts),
        storePath = config.storePath?.trim()?.takeIf { it.isNotEmpty() && config.enabled },
        networkScopeKey = config.networkScopeKey?.trim()?.takeIf { it.isNotEmpty() },
    )

private fun normalizeMarkerForStep(
    kind: TcpChainStepKind,
    marker: String,
): String {
    val defaultValue = if (kind.isTlsPrelude) DefaultTlsRecordMarker else DefaultSplitMarker
    return normalizeOffsetExpression(marker, defaultValue)
}

private fun normalizeTcpChainStep(step: TcpChainStepModel): TcpChainStepModel =
    normalizeTcpChainStepModel(step.copy(marker = normalizeMarkerForStep(step.kind, step.marker)))
