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
import com.poyka.ripdpi.data.effectiveIpIdMode
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

@Suppress("LongParameterList")
class RipDpiProxyUIPreferences(
    val protocols: RipDpiProtocolConfig = RipDpiProtocolConfig(),
    val parserEvasions: RipDpiParserEvasionConfig = RipDpiParserEvasionConfig(),
    adaptiveFallback: RipDpiAdaptiveFallbackConfig = RipDpiAdaptiveFallbackConfig(),
    val wsTunnel: RipDpiWsTunnelConfig = RipDpiWsTunnelConfig(),
    listen: RipDpiListenConfig = RipDpiListenConfig(),
    chains: RipDpiChainConfig = RipDpiChainConfig(),
    fakePackets: RipDpiFakePacketConfig = RipDpiFakePacketConfig(),
    quic: RipDpiQuicConfig = RipDpiQuicConfig(),
    hosts: RipDpiHostsConfig = RipDpiHostsConfig(),
    relay: RipDpiRelayConfig = RipDpiRelayConfig(),
    warp: RipDpiWarpConfig = RipDpiWarpConfig(),
    hostAutolearn: RipDpiHostAutolearnConfig = RipDpiHostAutolearnConfig(),
    nativeLogLevel: String? = null,
    runtimeContext: RipDpiRuntimeContext? = null,
    logContext: RipDpiLogContext? = null,
    val rootMode: Boolean = false,
    val rootHelperSocketPath: String? = null,
) : RipDpiProxyPreferences {
    val listen: RipDpiListenConfig = normalizeListenConfig(listen)
    val chains: RipDpiChainConfig = normalizeChainConfig(chains)
    val fakePackets: RipDpiFakePacketConfig = normalizeFakePacketConfig(fakePackets)
    val adaptiveFallback: RipDpiAdaptiveFallbackConfig = normalizeAdaptiveFallbackConfig(adaptiveFallback)
    val quic: RipDpiQuicConfig = normalizeQuicConfig(quic)
    val hosts: RipDpiHostsConfig = normalizeHostsConfig(hosts)
    val relay: RipDpiRelayConfig = normalizeRelayConfig(relay)
    val warp: RipDpiWarpConfig = normalizeWarpConfig(warp)
    val hostAutolearn: RipDpiHostAutolearnConfig = normalizeHostAutolearnConfig(hostAutolearn)
    val nativeLogLevel: String? = nativeLogLevel?.trim()?.takeIf { it.isNotEmpty() }
    val runtimeContext: RipDpiRuntimeContext? = normalizeRuntimeContext(runtimeContext)
    val logContext: RipDpiLogContext? = normalizeLogContext(logContext)
    val chainSummary: String = formatChainSummary(this.chains.tcpSteps, this.chains.udpSteps)

    override fun toNativeConfigJson(): String =
        RipDpiProxyJsonCodec.encodeUiPreferences(
            this,
            rootMode = rootMode,
            rootHelperSocketPath = rootHelperSocketPath,
        )

    fun withSessionOverrides(
        hostAutolearnStorePath: String? = hostAutolearn.storePath,
        networkScopeKey: String? = hostAutolearn.networkScopeKey,
        runtimeContext: RipDpiRuntimeContext? = this.runtimeContext,
        logContext: RipDpiLogContext? = this.logContext,
    ): RipDpiProxyUIPreferences =
        RipDpiProxyUIPreferences(
            listen = listen,
            protocols = protocols,
            chains = chains,
            fakePackets = fakePackets,
            parserEvasions = parserEvasions,
            adaptiveFallback = adaptiveFallback,
            quic = quic,
            hosts = hosts,
            relay = relay,
            warp = warp,
            hostAutolearn =
                hostAutolearn.copy(
                    storePath = hostAutolearnStorePath ?: hostAutolearn.storePath,
                    networkScopeKey = networkScopeKey ?: hostAutolearn.networkScopeKey,
                ),
            wsTunnel = wsTunnel,
            nativeLogLevel = nativeLogLevel,
            runtimeContext = runtimeContext ?: this.runtimeContext,
            logContext = logContext ?: this.logContext,
            rootMode = rootMode,
            rootHelperSocketPath = rootHelperSocketPath,
        )

    companion object {
        fun fromSettings(
            settings: AppSettings,
            hostAutolearnStorePath: String? = null,
            networkScopeKey: String? = null,
            runtimeContext: RipDpiRuntimeContext? = null,
            logContext: RipDpiLogContext? = null,
            rootMode: Boolean = false,
            rootHelperSocketPath: String? = null,
        ): RipDpiProxyUIPreferences =
            RipDpiProxyUIPreferences(
                listen = buildListenConfig(settings),
                protocols = buildProtocolConfig(settings),
                chains = buildChainConfig(settings),
                fakePackets = buildFakePacketConfig(settings),
                parserEvasions = buildParserEvasionConfig(settings),
                adaptiveFallback = buildAdaptiveFallbackConfig(settings),
                quic = buildQuicConfig(settings),
                hosts = buildHostsConfig(settings),
                relay = buildRelayConfig(settings),
                warp = buildWarpConfig(settings),
                hostAutolearn = buildHostAutolearnConfig(settings, hostAutolearnStorePath, networkScopeKey),
                wsTunnel = buildWsTunnelConfig(settings),
                runtimeContext = runtimeContext,
                logContext = logContext,
                rootMode = rootMode,
                rootHelperSocketPath = rootHelperSocketPath,
            )

        private fun buildChainConfig(settings: AppSettings): RipDpiChainConfig =
            RipDpiChainConfig(
                groupActivationFilter = settings.effectiveGroupActivationFilter(),
                tcpSteps = settings.effectiveTcpChainSteps(),
                udpSteps = settings.effectiveUdpChainSteps(),
                anyProtocol = settings.desyncAnyProtocol,
            )

        private fun buildFakePacketConfig(settings: AppSettings): RipDpiFakePacketConfig =
            RipDpiFakePacketConfig(
                fakeTtl = settings.fakeTtl.takeIf { it > 0 } ?: 8,
                adaptiveFakeTtlEnabled = settings.adaptiveFakeTtlEnabled,
                adaptiveFakeTtlDelta = settings.effectiveAdaptiveFakeTtlDelta(),
                adaptiveFakeTtlMin = settings.effectiveAdaptiveFakeTtlMin(),
                adaptiveFakeTtlMax = settings.effectiveAdaptiveFakeTtlMax(),
                adaptiveFakeTtlFallback = settings.effectiveAdaptiveFakeTtlFallback(),
                fakeSni = settings.fakeSni.ifEmpty { DefaultFakeSni },
                httpFakeProfile = settings.effectiveHttpFakeProfile(),
                fakeTlsSource =
                    com.poyka.ripdpi.data
                        .normalizeFakeTlsSource(settings.fakeTlsSource),
                fakeTlsSecondaryProfile = settings.fakeTlsSecondaryProfile,
                fakeTcpTimestampEnabled = settings.fakeTcpTimestampEnabled,
                fakeTcpTimestampDeltaTicks = settings.fakeTcpTimestampDeltaTicks,
                fakeTlsUseOriginal = settings.fakeTlsUseOriginal,
                fakeTlsRandomize = settings.fakeTlsRandomize,
                fakeTlsDupSessionId = settings.fakeTlsDupSessionId,
                fakeTlsPadEncap = settings.fakeTlsPadEncap,
                fakeTlsSize = settings.fakeTlsSize,
                fakeTlsSniMode = settings.effectiveFakeTlsSniMode(),
                tlsFakeProfile = settings.effectiveTlsFakeProfile(),
                udpFakeProfile = settings.effectiveUdpFakeProfile(),
                fakeOffsetMarker = settings.effectiveFakeOffsetMarker(),
                oobChar = settings.oobData.firstOrNull() ?: 'a',
                dropSack = settings.dropSack,
                windowClamp = settings.windowClamp.takeIf { it > 0 },
                wsizeWindow = settings.wsizeWindow.takeIf { it > 0 },
                wsizeScale = settings.wsizeScale.takeIf { it >= -1 },
                stripTimestamps = settings.stripTimestamps,
                ipIdMode = settings.effectiveIpIdMode(),
                quicBindLowPort = settings.quicBindLowPort,
                quicMigrateAfterHandshake = settings.quicMigrateAfterHandshake,
                entropyMode =
                    com.poyka.ripdpi.data
                        .entropyModeFromProto(settings.entropyMode),
                entropyPaddingTargetPermil =
                    settings.entropyPaddingTargetPermil.takeIf { it > 0 } ?: DefaultEntropyPaddingTargetPermil,
                entropyPaddingMax = settings.entropyPaddingMax.takeIf { it > 0 } ?: DefaultEntropyPaddingMax,
                shannonEntropyTargetPermil =
                    settings.shannonEntropyTargetPermil.takeIf { it > 0 } ?: DefaultShannonEntropyTargetPermil,
                tlsFingerprintProfile = normalizeTlsFingerprintProfile(settings.tlsFingerprintProfile),
            )

        private fun buildAdaptiveFallbackConfig(settings: AppSettings): RipDpiAdaptiveFallbackConfig {
            val adaptive = settings.toAdaptiveFallbackSettingsModel()
            return RipDpiAdaptiveFallbackConfig(
                enabled = adaptive.enabled,
                torst = adaptive.torst,
                tlsErr = adaptive.tlsErr,
                httpRedirect = adaptive.httpRedirect,
                connectFailure = adaptive.connectFailure,
                autoSort = adaptive.autoSort,
                cacheTtlSeconds = adaptive.cacheTtlSeconds,
                cachePrefixV4 = adaptive.cachePrefixV4,
                strategyEvolution = settings.strategyEvolution,
                evolutionEpsilon = settings.evolutionEpsilon.takeIf { it in 0.0..1.0 } ?: DefaultEvolutionEpsilon,
            )
        }

        private fun buildQuicConfig(settings: AppSettings): RipDpiQuicConfig =
            RipDpiQuicConfig(
                initialMode = settings.effectiveQuicInitialMode(),
                supportV1 = settings.effectiveQuicSupportV1(),
                supportV2 = settings.effectiveQuicSupportV2(),
                fakeProfile = settings.effectiveQuicFakeProfile(),
                fakeHost = settings.effectiveQuicFakeHost(),
            )

        private fun buildHostsConfig(settings: AppSettings): RipDpiHostsConfig =
            RipDpiHostsConfig(
                mode =
                    settings.hostsMode
                        .ifEmpty { RipDpiHostsConfig.Mode.Disable.wireName }
                        .let(RipDpiHostsConfig.Mode::fromWireName),
                entries =
                    when (settings.hostsMode) {
                        "blacklist" -> settings.hostsBlacklist
                        "whitelist" -> settings.hostsWhitelist
                        else -> null
                    },
            )

        private fun buildWarpConfig(settings: AppSettings): RipDpiWarpConfig {
            val warp = settings.toWarpSettingsModel()
            return RipDpiWarpConfig(
                enabled = warp.enabled,
                routeMode = warp.routeMode,
                routeHosts = warp.routeHosts,
                builtInRulesEnabled = warp.builtInRulesEnabled,
                endpointSelectionMode = warp.endpointSelectionMode,
                manualEndpoint =
                    RipDpiWarpManualEndpointConfig(
                        host = warp.manualEndpoint.host,
                        ipv4 = warp.manualEndpoint.ipv4,
                        ipv6 = warp.manualEndpoint.ipv6,
                        port = warp.manualEndpoint.port,
                    ),
                scannerEnabled = warp.scannerEnabled,
                scannerParallelism = warp.scannerParallelism,
                scannerMaxRttMs = warp.scannerMaxRttMs,
                amneziaPreset = warp.amneziaPreset,
                amnezia =
                    RipDpiWarpAmneziaConfig(
                        enabled = warp.amnezia.enabled,
                        jc = warp.amnezia.jc,
                        jmin = warp.amnezia.jmin,
                        jmax = warp.amnezia.jmax,
                        h1 = warp.amnezia.h1,
                        h2 = warp.amnezia.h2,
                        h3 = warp.amnezia.h3,
                        h4 = warp.amnezia.h4,
                        s1 = warp.amnezia.s1,
                        s2 = warp.amnezia.s2,
                        s3 = warp.amnezia.s3,
                        s4 = warp.amnezia.s4,
                    ),
                localSocksPort = DefaultWarpLocalSocksPort,
            )
        }

        private fun buildRelayConfig(settings: AppSettings): RipDpiRelayConfig {
            val relay = settings.toRelaySettingsModel()
            return RipDpiRelayConfig(
                enabled = relay.enabled,
                kind = relay.kind,
                profileId = relay.profileId,
                outboundBindIp = relay.profile.outboundBindIp,
                server = relay.profile.server,
                serverPort = relay.profile.serverPort,
                serverName = relay.profile.serverName,
                realityPublicKey = relay.profile.realityPublicKey,
                realityShortId = relay.profile.realityShortId,
                vlessTransport = relay.profile.vlessTransport,
                xhttpPath = relay.profile.xhttpPath,
                xhttpHost = relay.profile.xhttpHost,
                cloudflareTunnelMode = relay.profile.cloudflareTunnelMode,
                cloudflarePublishLocalOriginUrl = relay.profile.cloudflarePublishLocalOriginUrl,
                cloudflareCredentialsRef = relay.profile.cloudflareCredentialsRef,
                chainEntryServer = relay.profile.chainEntryServer,
                chainEntryPort = relay.profile.chainEntryPort,
                chainEntryServerName = relay.profile.chainEntryServerName,
                chainEntryPublicKey = relay.profile.chainEntryPublicKey,
                chainEntryShortId = relay.profile.chainEntryShortId,
                chainEntryProfileId = relay.profile.chainEntryProfileId,
                chainExitServer = relay.profile.chainExitServer,
                chainExitPort = relay.profile.chainExitPort,
                chainExitServerName = relay.profile.chainExitServerName,
                chainExitPublicKey = relay.profile.chainExitPublicKey,
                chainExitShortId = relay.profile.chainExitShortId,
                chainExitProfileId = relay.profile.chainExitProfileId,
                masqueUrl = relay.profile.masqueUrl,
                masqueUseHttp2Fallback = relay.profile.masqueUseHttp2Fallback,
                masqueCloudflareGeohashEnabled = relay.profile.masqueCloudflareGeohashEnabled,
                tuicZeroRtt = relay.profile.tuicZeroRtt,
                tuicCongestionControl = relay.profile.tuicCongestionControl,
                shadowTlsInnerProfileId = relay.profile.shadowTlsInnerProfileId,
                naivePath = relay.profile.naivePath,
                localSocksHost = relay.profile.localSocksHost,
                localSocksPort = relay.profile.localSocksPort,
                udpEnabled = relay.profile.udpEnabled,
                tcpFallbackEnabled = relay.profile.tcpFallbackEnabled,
                finalmask =
                    RipDpiRelayFinalmaskConfig(
                        type = relay.profile.finalmask.type,
                        headerHex = relay.profile.finalmask.headerHex,
                        trailerHex = relay.profile.finalmask.trailerHex,
                        randRange = relay.profile.finalmask.randRange,
                        sudokuSeed = relay.profile.finalmask.sudokuSeed,
                        fragmentPackets = relay.profile.finalmask.fragmentPackets,
                        fragmentMinBytes = relay.profile.finalmask.fragmentMinBytes,
                        fragmentMaxBytes = relay.profile.finalmask.fragmentMaxBytes,
                    ),
            )
        }

        private fun buildHostAutolearnConfig(
            settings: AppSettings,
            hostAutolearnStorePath: String?,
            networkScopeKey: String?,
        ): RipDpiHostAutolearnConfig =
            RipDpiHostAutolearnConfig(
                enabled = settings.hostAutolearnEnabled,
                penaltyTtlHours = settings.hostAutolearnPenaltyTtlHours,
                maxHosts = settings.hostAutolearnMaxHosts,
                storePath = hostAutolearnStorePath,
                networkScopeKey = networkScopeKey,
            )
    }
}

private fun buildListenConfig(settings: AppSettings): RipDpiListenConfig =
    RipDpiListenConfig(
        ip = settings.proxyIp.ifEmpty { "127.0.0.1" },
        port = settings.proxyPort.takeIf { it > 0 } ?: 1080,
        maxConnections = settings.maxConnections.takeIf { it > 0 } ?: 512,
        bufferSize = settings.bufferSize.takeIf { it > 0 } ?: 16384,
        tcpFastOpen = settings.tcpFastOpen,
        defaultTtl = if (settings.customTtl) settings.defaultTtl else 0,
        customTtl = settings.customTtl,
        freezeDetectionEnabled = settings.freezeDetectionEnabled,
    )

private fun buildProtocolConfig(settings: AppSettings): RipDpiProtocolConfig =
    RipDpiProtocolConfig(
        resolveDomains = !settings.noDomain,
        desyncHttp = settings.desyncHttp,
        desyncHttps = settings.desyncHttps,
        desyncUdp = settings.desyncUdp,
    )

private fun buildParserEvasionConfig(settings: AppSettings): RipDpiParserEvasionConfig =
    RipDpiParserEvasionConfig(
        hostMixedCase = settings.hostMixedCase,
        domainMixedCase = settings.domainMixedCase,
        hostRemoveSpaces = settings.hostRemoveSpaces,
        httpMethodEol = settings.httpMethodEol,
        httpMethodSpace = settings.httpMethodSpace,
        httpUnixEol = settings.httpUnixEol,
        httpHostPad = settings.httpHostPad,
        httpHostExtraSpace = settings.httpHostExtraSpace,
        httpHostTab = settings.httpHostTab,
    )

private fun buildWsTunnelConfig(settings: AppSettings): RipDpiWsTunnelConfig {
    val mode =
        settings.wsTunnelMode.ifEmpty {
            if (settings.wsTunnelEnabled) "always" else "off"
        }
    return RipDpiWsTunnelConfig(
        enabled = mode != "off",
        mode = mode,
    )
}

fun RipDpiProxyUIPreferences.deriveStrategyLaneFamilies(activeDns: ActiveDnsSettings? = null): StrategyLaneFamilies =
    deriveStrategyLaneFamilies(
        tcpSteps = chains.tcpSteps,
        tcpRotationCandidateCount = chains.tcpRotation?.candidates?.size ?: 0,
        udpSteps = chains.udpSteps,
        desyncUdp = protocols.desyncUdp,
        quicInitialMode = quic.initialMode,
        quicFakeProfile = quic.fakeProfile,
        activeDns = activeDns,
    )

@Suppress("LongMethod")
fun RipDpiProxyUIPreferences.applyToSettings(settings: AppSettings): AppSettings =
    settings
        .toBuilder()
        .apply {
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
            setGroupActivationFilterCompat(chains.groupActivationFilter)
            setStrategyChains(
                tcpSteps = chains.tcpSteps,
                udpSteps = chains.udpSteps,
            )
            setDesyncAnyProtocol(chains.anyProtocol)
            setFakeTtl(fakePackets.fakeTtl)
            setAdaptiveFakeTtlEnabled(fakePackets.adaptiveFakeTtlEnabled)
            setAdaptiveFakeTtlDelta(fakePackets.adaptiveFakeTtlDelta)
            setAdaptiveFakeTtlMin(fakePackets.adaptiveFakeTtlMin)
            setAdaptiveFakeTtlMax(fakePackets.adaptiveFakeTtlMax)
            setAdaptiveFakeTtlFallback(fakePackets.adaptiveFakeTtlFallback)
            setFakeSni(fakePackets.fakeSni)
            setFakeTlsSource(
                com.poyka.ripdpi.data
                    .normalizeFakeTlsSource(fakePackets.fakeTlsSource),
            )
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
            setWindowClamp(fakePackets.windowClamp ?: 0)
            setWsizeWindow(fakePackets.wsizeWindow ?: 0)
            setWsizeScale(fakePackets.wsizeScale ?: 0)
            setStripTimestamps(fakePackets.stripTimestamps)
            setIpIdMode(normalizeIpIdMode(fakePackets.ipIdMode))
            setQuicBindLowPort(fakePackets.quicBindLowPort)
            setQuicMigrateAfterHandshake(fakePackets.quicMigrateAfterHandshake)
            setEntropyMode(
                com.poyka.ripdpi.data
                    .entropyModeToProto(fakePackets.entropyMode),
            )
            setEntropyPaddingTargetPermil(fakePackets.entropyPaddingTargetPermil.coerceAtLeast(0))
            setEntropyPaddingMax(fakePackets.entropyPaddingMax.coerceAtLeast(0))
            setShannonEntropyTargetPermil(fakePackets.shannonEntropyTargetPermil.coerceAtLeast(0))
            setTlsFingerprintProfile(normalizeTlsFingerprintProfile(fakePackets.tlsFingerprintProfile))
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
            setRelayMasqueUrl(relay.masqueUrl)
            setRelayMasqueUseHttp2Fallback(relay.masqueUseHttp2Fallback)
            setRelayMasqueCloudflareGeohashEnabled(relay.masqueCloudflareGeohashEnabled)
            setRelayLocalSocksHost(relay.localSocksHost)
            setRelayLocalSocksPort(relay.localSocksPort)
            setRelayUdpEnabled(relay.udpEnabled)
            setRelayTcpFallbackEnabled(relay.tcpFallbackEnabled)
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
            setHostAutolearnEnabled(hostAutolearn.enabled)
            setHostAutolearnPenaltyTtlHours(hostAutolearn.penaltyTtlHours)
            setHostAutolearnMaxHosts(hostAutolearn.maxHosts)
            setWsTunnelEnabled(wsTunnel.enabled)
            setWsTunnelMode(wsTunnel.mode.orEmpty())
        }.build()

internal const val MaxValidPortNumber = 65535
private const val DefaultChainHopPort = 443
