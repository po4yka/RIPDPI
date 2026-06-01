package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.entropyModeToProto
import com.poyka.ripdpi.data.normalizeFakeTlsSource
import com.poyka.ripdpi.data.normalizeIpIdMode
import com.poyka.ripdpi.data.normalizeRelayVlessTransport
import com.poyka.ripdpi.data.normalizeTlsFingerprintProfile
import com.poyka.ripdpi.data.setGroupActivationFilterCompat
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.proto.AppSettings

fun RipDpiProxyUIPreferences.applyToSettings(settings: AppSettings): AppSettings =
    settings
        .toBuilder()
        .apply {
            applyListenAndProtocolPreferences(this@applyToSettings)
            applyDesyncPreferences(this@applyToSettings)
            applyFakePacketPreferences(this@applyToSettings)
            applyParserAndAdaptivePreferences(this@applyToSettings)
            applyQuicAndHostPreferences(this@applyToSettings)
            applyRelayPreferences(this@applyToSettings)
            applyWarpPreferences(this@applyToSettings)
            applyRuntimePreferences(this@applyToSettings)
        }.build()

private fun AppSettings.Builder.applyListenAndProtocolPreferences(preferences: RipDpiProxyUIPreferences) {
    val listen = preferences.listen
    val protocols = preferences.protocols
    setProxyIp(listen.ip)
    setProxyPort(listen.port)
    setMaxConnections(listen.maxConnections)
    setBufferSize(listen.bufferSize)
    setTcpFastOpen(listen.tcpFastOpen)
    setDefaultTtl(if (listen.customTtl) listen.defaultTtl else 0)
    setCustomTtl(listen.customTtl)
    setFreezeDetectionEnabled(listen.freezeDetectionEnabled)
    setNoDomain(!protocols.resolveDomains)
    setDesyncHttp(protocols.desyncHttp)
    setDesyncHttps(protocols.desyncHttps)
    setDesyncUdp(protocols.desyncUdp)
    setUdpAssociateEnabled(protocols.udpAssociateEnabled)
}

private fun AppSettings.Builder.applyDesyncPreferences(preferences: RipDpiProxyUIPreferences) {
    val chains = preferences.chains
    setGroupActivationFilterCompat(chains.groupActivationFilter)
    setStrategyChains(
        tcpSteps = chains.tcpSteps,
        udpSteps = chains.udpSteps,
    )
    setDesyncAnyProtocol(chains.anyProtocol)
}

private fun AppSettings.Builder.applyFakePacketPreferences(preferences: RipDpiProxyUIPreferences) {
    val fakePackets = preferences.fakePackets
    setFakeTtl(fakePackets.fakeTtl)
    setAdaptiveFakeTtlEnabled(fakePackets.adaptiveFakeTtlEnabled)
    setAdaptiveFakeTtlDelta(fakePackets.adaptiveFakeTtlDelta)
    setAdaptiveFakeTtlMin(fakePackets.adaptiveFakeTtlMin)
    setAdaptiveFakeTtlMax(fakePackets.adaptiveFakeTtlMax)
    setAdaptiveFakeTtlFallback(fakePackets.adaptiveFakeTtlFallback)
    setFakeSni(fakePackets.fakeSni)
    setFakeTlsSource(normalizeFakeTlsSource(fakePackets.fakeTlsSource))
    setFakeTlsSecondaryProfile(fakePackets.fakeTlsSecondaryProfile)
    setFakeTcpTimestampEnabled(fakePackets.fakeTcpTimestampEnabled)
    setFakeTcpTimestampDeltaTicks(fakePackets.fakeTcpTimestampDeltaTicks)
    setHttpFakeProfile(fakePackets.httpFakeProfile)
    setFakeTlsUseOriginal(fakePackets.fakeTlsUseOriginal)
    setFakeTlsRandomize(fakePackets.fakeTlsRandomize)
    setFakeTlsDupSessionId(fakePackets.fakeTlsDupSessionId)
    setFakeTlsPadEncap(fakePackets.fakeTlsPadEncap)
    setFakeTlsSize(fakePackets.fakeTlsSize)
    setFakeTlsSniMode(fakePackets.fakeTlsSniMode)
    setTlsFakeProfile(fakePackets.tlsFakeProfile)
    setUdpFakeProfile(fakePackets.udpFakeProfile)
    setFakeOffsetMarker(fakePackets.fakeOffsetMarker)
    setOobData(fakePackets.oobChar.toString())
    setDropSack(fakePackets.dropSack)
    setFakeMd5Sig(fakePackets.md5sig)
    setWindowClamp(fakePackets.windowClamp ?: 0)
    setWsizeWindow(fakePackets.wsizeWindow ?: 0)
    setWsizeScale(fakePackets.wsizeScale ?: 0)
    setStripTimestamps(fakePackets.stripTimestamps)
    setIpIdMode(normalizeIpIdMode(fakePackets.ipIdMode))
    setQuicBindLowPort(fakePackets.quicBindLowPort)
    setQuicMigrateAfterHandshake(fakePackets.quicMigrateAfterHandshake)
    setEntropyMode(entropyModeToProto(fakePackets.entropyMode))
    setEntropyPaddingTargetPermil(fakePackets.entropyPaddingTargetPermil.coerceAtLeast(0))
    setEntropyPaddingMax(fakePackets.entropyPaddingMax.coerceAtLeast(0))
    setShannonEntropyTargetPermil(fakePackets.shannonEntropyTargetPermil.coerceAtLeast(0))
    setTlsFingerprintProfile(normalizeTlsFingerprintProfile(fakePackets.tlsFingerprintProfile))
}

private fun AppSettings.Builder.applyParserAndAdaptivePreferences(preferences: RipDpiProxyUIPreferences) {
    val parserEvasions = preferences.parserEvasions
    val adaptiveFallback = preferences.adaptiveFallback
    setHostMixedCase(parserEvasions.hostMixedCase)
    setDomainMixedCase(parserEvasions.domainMixedCase)
    setHostRemoveSpaces(parserEvasions.hostRemoveSpaces)
    setHttpMethodEol(parserEvasions.httpMethodEol)
    setHttpMethodSpace(parserEvasions.httpMethodSpace)
    setHttpUnixEol(parserEvasions.httpUnixEol)
    setHttpHostPad(parserEvasions.httpHostPad)
    setAdaptiveFallbackEnabled(adaptiveFallback.enabled)
    setAdaptiveFallbackTorst(adaptiveFallback.torst)
    setAdaptiveFallbackTlsErr(adaptiveFallback.tlsErr)
    setAdaptiveFallbackHttpRedirect(adaptiveFallback.httpRedirect)
    setAdaptiveFallbackConnectFailure(adaptiveFallback.connectFailure)
    setAdaptiveFallbackAutoSort(adaptiveFallback.autoSort)
    setAdaptiveFallbackCacheTtlSeconds(adaptiveFallback.cacheTtlSeconds)
    setAdaptiveFallbackCachePrefixV4(adaptiveFallback.cachePrefixV4)
    setStrategyEvolution(adaptiveFallback.strategyEvolution)
    setEvolutionEpsilon(adaptiveFallback.evolutionEpsilon.coerceIn(0.0, 1.0))
    setEvolutionExperimentTtlMs(adaptiveFallback.evolutionExperimentTtlMs.coerceAtLeast(0))
    setEvolutionDecayHalfLifeMs(adaptiveFallback.evolutionDecayHalfLifeMs.coerceAtLeast(0))
    setEvolutionCooldownAfterFailures(adaptiveFallback.evolutionCooldownAfterFailures.coerceAtLeast(0))
    setEvolutionCooldownMs(adaptiveFallback.evolutionCooldownMs.coerceAtLeast(0))
}

private fun AppSettings.Builder.applyQuicAndHostPreferences(preferences: RipDpiProxyUIPreferences) {
    val quic = preferences.quic
    val hosts = preferences.hosts
    setQuicInitialMode(quic.initialMode)
    setQuicSupportV1(quic.supportV1)
    setQuicSupportV2(quic.supportV2)
    setQuicFakeProfile(quic.fakeProfile)
    setQuicFakeHost(quic.fakeHost)
    setHostsMode(hosts.mode.wireName)
    when (hosts.mode) {
        RipDpiHostsConfig.Mode.Disable -> {
            setHostsBlacklist("")
            setHostsWhitelist("")
        }

        RipDpiHostsConfig.Mode.Blacklist -> {
            setHostsBlacklist(hosts.entries.orEmpty())
            setHostsWhitelist("")
        }

        RipDpiHostsConfig.Mode.Whitelist -> {
            setHostsBlacklist("")
            setHostsWhitelist(hosts.entries.orEmpty())
        }
    }
}

private fun AppSettings.Builder.applyRelayPreferences(preferences: RipDpiProxyUIPreferences) {
    val relay = preferences.relay
    setRelayEnabled(relay.enabled)
    setRelayKind(relay.kind)
    setRelayProfileId(relay.profileId)
    setRelayOutboundBindIp(relay.outboundBindIp)
    setRelayServer(relay.server)
    setRelayServerPort(relay.serverPort)
    setRelayServerName(relay.serverName)
    setRelayRealityPublicKey(relay.realityPublicKey)
    setRelayRealityShortId(relay.realityShortId)
    setRelayVlessTransport(normalizeRelayVlessTransport(relay.vlessTransport, relay.kind))
    setRelayXhttpPath(relay.xhttpPath)
    setRelayXhttpHost(relay.xhttpHost)
    setRelayChainEntryServer("")
    setRelayChainEntryPort(DefaultChainHopPort)
    setRelayChainEntryServerName("")
    setRelayChainEntryPublicKey("")
    setRelayChainEntryShortId("")
    setRelayChainEntryProfileId(if (relay.kind == "chain_relay") relay.chainEntryProfileId else "")
    setRelayChainExitServer("")
    setRelayChainExitPort(DefaultChainHopPort)
    setRelayChainExitServerName("")
    setRelayChainExitPublicKey("")
    setRelayChainExitShortId("")
    setRelayChainExitProfileId(if (relay.kind == "chain_relay") relay.chainExitProfileId else "")
    clearRelayChainMiddleProfileIds()
    if (relay.kind == "chain_relay") {
        addAllRelayChainMiddleProfileIds(relay.chainMiddleProfileIds)
    }
    setRelayMasqueUrl(relay.masqueUrl)
    setRelayMasqueUseHttp2Fallback(relay.masqueUseHttp2Fallback)
    setRelayMasqueCloudflareGeohashEnabled(relay.masqueCloudflareGeohashEnabled)
    setRelayLocalSocksHost(relay.localSocksHost)
    setRelayLocalSocksPort(relay.localSocksPort)
    setRelayUdpEnabled(relay.udpEnabled)
    setRelayTcpFallbackEnabled(relay.tcpFallbackEnabled)
}

private fun AppSettings.Builder.applyWarpPreferences(preferences: RipDpiProxyUIPreferences) {
    val warp = preferences.warp
    setWarpEnabled(warp.enabled)
    setWarpRouteMode(warp.routeMode)
    setWarpRouteHosts(warp.routeHosts)
    setWarpBuiltinRulesEnabled(warp.builtInRulesEnabled)
    setWarpEndpointSelectionMode(warp.endpointSelectionMode)
    setWarpManualEndpointHost(warp.manualEndpoint.host)
    setWarpManualEndpointV4(warp.manualEndpoint.ipv4)
    setWarpManualEndpointV6(warp.manualEndpoint.ipv6)
    setWarpManualEndpointPort(warp.manualEndpoint.port)
    setWarpScannerEnabled(warp.scannerEnabled)
    setWarpScannerParallelism(warp.scannerParallelism)
    setWarpScannerMaxRttMs(warp.scannerMaxRttMs)
    setWarpAmneziaPreset(warp.amneziaPreset)
    setWarpAmneziaEnabled(warp.amnezia.enabled)
    setWarpAmneziaJc(warp.amnezia.jc)
    setWarpAmneziaJmin(warp.amnezia.jmin)
    setWarpAmneziaJmax(warp.amnezia.jmax)
    setWarpAmneziaH1(warp.amnezia.h1)
    setWarpAmneziaH2(warp.amnezia.h2)
    setWarpAmneziaH3(warp.amnezia.h3)
    setWarpAmneziaH4(warp.amnezia.h4)
    setWarpAmneziaS1(warp.amnezia.s1)
    setWarpAmneziaS2(warp.amnezia.s2)
    setWarpAmneziaS3(warp.amnezia.s3)
    setWarpAmneziaS4(warp.amnezia.s4)
}

private fun AppSettings.Builder.applyRuntimePreferences(preferences: RipDpiProxyUIPreferences) {
    val hostAutolearn = preferences.hostAutolearn
    val wsTunnel = preferences.wsTunnel
    setHostAutolearnEnabled(hostAutolearn.enabled)
    setHostAutolearnPenaltyTtlHours(hostAutolearn.penaltyTtlHours)
    setHostAutolearnMaxHosts(hostAutolearn.maxHosts)
    setWsTunnelEnabled(wsTunnel.enabled)
    setWsTunnelMode(wsTunnel.mode.orEmpty())
    setWsTunnelFakeSni(wsTunnel.fakeSni.orEmpty())
    setWsTunnelAllowInsecureSni(wsTunnel.allowInsecureSni)
}

private const val DefaultChainHopPort = 443
