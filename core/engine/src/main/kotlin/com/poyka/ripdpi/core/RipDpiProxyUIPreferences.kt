package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.DefaultSplitMarker
import com.poyka.ripdpi.data.DefaultTlsRecordMarker
import com.poyka.ripdpi.data.FakePayloadProfileCompatDefault
import com.poyka.ripdpi.data.QuicFakeProfileDisabled
import com.poyka.ripdpi.data.QuicInitialModeRouteAndCache
import com.poyka.ripdpi.data.StrategyLaneFamilies
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
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
import com.poyka.ripdpi.data.normalizeFakeTlsSniMode
import com.poyka.ripdpi.data.normalizeHostAutolearnMaxHosts
import com.poyka.ripdpi.data.normalizeHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.normalizeHttpFakeProfile
import com.poyka.ripdpi.data.normalizeOffsetExpression
import com.poyka.ripdpi.data.normalizeQuicFakeHost
import com.poyka.ripdpi.data.normalizeQuicFakeProfile
import com.poyka.ripdpi.data.normalizeQuicInitialMode
import com.poyka.ripdpi.data.normalizeTcpChainStepModel
import com.poyka.ripdpi.data.normalizeTlsFakeProfile
import com.poyka.ripdpi.data.normalizeUdpChainStepModel
import com.poyka.ripdpi.data.normalizeUdpFakeProfile
import com.poyka.ripdpi.data.setGroupActivationFilterCompat
import com.poyka.ripdpi.data.setStrategyChains
import com.poyka.ripdpi.proto.AppSettings

class RipDpiProxyUIPreferences(
    val protocols: RipDpiProtocolConfig = RipDpiProtocolConfig(),
    val parserEvasions: RipDpiParserEvasionConfig = RipDpiParserEvasionConfig(),
    val wsTunnel: RipDpiWsTunnelConfig = RipDpiWsTunnelConfig(),
    listen: RipDpiListenConfig = RipDpiListenConfig(),
    chains: RipDpiChainConfig = RipDpiChainConfig(),
    fakePackets: RipDpiFakePacketConfig = RipDpiFakePacketConfig(),
    quic: RipDpiQuicConfig = RipDpiQuicConfig(),
    hosts: RipDpiHostsConfig = RipDpiHostsConfig(),
    hostAutolearn: RipDpiHostAutolearnConfig = RipDpiHostAutolearnConfig(),
    nativeLogLevel: String? = null,
    runtimeContext: RipDpiRuntimeContext? = null,
    logContext: RipDpiLogContext? = null,
) : RipDpiProxyPreferences {
    val listen: RipDpiListenConfig = normalizeListenConfig(listen)
    val chains: RipDpiChainConfig = normalizeChainConfig(chains)
    val fakePackets: RipDpiFakePacketConfig = normalizeFakePacketConfig(fakePackets)
    val quic: RipDpiQuicConfig = normalizeQuicConfig(quic)
    val hosts: RipDpiHostsConfig = normalizeHostsConfig(hosts)
    val hostAutolearn: RipDpiHostAutolearnConfig = normalizeHostAutolearnConfig(hostAutolearn)
    val nativeLogLevel: String? = nativeLogLevel?.trim()?.takeIf { it.isNotEmpty() }
    val runtimeContext: RipDpiRuntimeContext? = normalizeRuntimeContext(runtimeContext)
    val logContext: RipDpiLogContext? = normalizeLogContext(logContext)
    val chainSummary: String = formatChainSummary(this.chains.tcpSteps, this.chains.udpSteps)

    override fun toNativeConfigJson(): String = RipDpiProxyJsonCodec.encodeUiPreferences(this)

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
            quic = quic,
            hosts = hosts,
            hostAutolearn =
                hostAutolearn.copy(
                    storePath = hostAutolearnStorePath ?: hostAutolearn.storePath,
                    networkScopeKey = networkScopeKey ?: hostAutolearn.networkScopeKey,
                ),
            wsTunnel = wsTunnel,
            nativeLogLevel = nativeLogLevel,
            runtimeContext = runtimeContext ?: this.runtimeContext,
            logContext = logContext ?: this.logContext,
        )

    companion object {
        fun fromSettings(
            settings: AppSettings,
            hostAutolearnStorePath: String? = null,
            networkScopeKey: String? = null,
            runtimeContext: RipDpiRuntimeContext? = null,
            logContext: RipDpiLogContext? = null,
        ): RipDpiProxyUIPreferences =
            RipDpiProxyUIPreferences(
                listen = buildListenConfig(settings),
                protocols = buildProtocolConfig(settings),
                chains = buildChainConfig(settings),
                fakePackets = buildFakePacketConfig(settings),
                parserEvasions = buildParserEvasionConfig(settings),
                quic = buildQuicConfig(settings),
                hosts = buildHostsConfig(settings),
                hostAutolearn = buildHostAutolearnConfig(settings, hostAutolearnStorePath, networkScopeKey),
                wsTunnel = buildWsTunnelConfig(settings),
                runtimeContext = runtimeContext,
                logContext = logContext,
            )

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

        private fun buildChainConfig(settings: AppSettings): RipDpiChainConfig =
            RipDpiChainConfig(
                groupActivationFilter = settings.effectiveGroupActivationFilter(),
                tcpSteps = settings.effectiveTcpChainSteps(),
                udpSteps = settings.effectiveUdpChainSteps(),
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
            )

        private fun buildParserEvasionConfig(settings: AppSettings): RipDpiParserEvasionConfig =
            RipDpiParserEvasionConfig(
                hostMixedCase = settings.hostMixedCase,
                domainMixedCase = settings.domainMixedCase,
                hostRemoveSpaces = settings.hostRemoveSpaces,
                httpMethodEol = settings.httpMethodEol,
                httpUnixEol = settings.httpUnixEol,
            )

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
    }
}

fun RipDpiProxyUIPreferences.deriveStrategyLaneFamilies(activeDns: ActiveDnsSettings? = null): StrategyLaneFamilies =
    deriveStrategyLaneFamilies(
        tcpSteps = chains.tcpSteps,
        udpSteps = chains.udpSteps,
        desyncUdp = protocols.desyncUdp,
        quicInitialMode = quic.initialMode,
        quicFakeProfile = quic.fakeProfile,
        activeDns = activeDns,
    )

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
            setFakeTtl(fakePackets.fakeTtl)
            setAdaptiveFakeTtlEnabled(fakePackets.adaptiveFakeTtlEnabled)
            setAdaptiveFakeTtlDelta(fakePackets.adaptiveFakeTtlDelta)
            setAdaptiveFakeTtlMin(fakePackets.adaptiveFakeTtlMin)
            setAdaptiveFakeTtlMax(fakePackets.adaptiveFakeTtlMax)
            setAdaptiveFakeTtlFallback(fakePackets.adaptiveFakeTtlFallback)
            setFakeSni(fakePackets.fakeSni)
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
            setHostMixedCase(parserEvasions.hostMixedCase)
            setDomainMixedCase(parserEvasions.domainMixedCase)
            setHostRemoveSpaces(parserEvasions.hostRemoveSpaces)
            setHttpMethodEol(parserEvasions.httpMethodEol)
            setHttpUnixEol(parserEvasions.httpUnixEol)
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
            setHostAutolearnEnabled(hostAutolearn.enabled)
            setHostAutolearnPenaltyTtlHours(hostAutolearn.penaltyTtlHours)
            setHostAutolearnMaxHosts(hostAutolearn.maxHosts)
            setWsTunnelEnabled(wsTunnel.enabled)
            setWsTunnelMode(wsTunnel.mode.orEmpty())
        }.build()

private fun normalizeListenConfig(config: RipDpiListenConfig): RipDpiListenConfig =
    config.copy(ip = config.ip.ifBlank { "127.0.0.1" })

private fun normalizeChainConfig(config: RipDpiChainConfig): RipDpiChainConfig =
    config.copy(
        groupActivationFilter = normalizeActivationFilter(config.groupActivationFilter),
        tcpSteps = config.tcpSteps.map(::normalizeTcpChainStep),
        udpSteps = config.udpSteps.map(::normalizeUdpChainStepModel),
    )

private fun normalizeFakePacketConfig(config: RipDpiFakePacketConfig): RipDpiFakePacketConfig {
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
        fakeTlsSniMode = normalizeFakeTlsSniMode(config.fakeTlsSniMode),
        tlsFakeProfile = normalizeTlsFakeProfile(config.tlsFakeProfile.ifBlank { FakePayloadProfileCompatDefault }),
        udpFakeProfile = normalizeUdpFakeProfile(config.udpFakeProfile.ifBlank { FakePayloadProfileCompatDefault }),
        fakeOffsetMarker = normalizeOffsetExpression(config.fakeOffsetMarker, DefaultFakeOffsetMarker),
    )
}

private fun normalizeQuicConfig(config: RipDpiQuicConfig): RipDpiQuicConfig =
    config.copy(
        initialMode = normalizeQuicInitialMode(config.initialMode.ifBlank { QuicInitialModeRouteAndCache }),
        fakeProfile = normalizeQuicFakeProfile(config.fakeProfile.ifBlank { QuicFakeProfileDisabled }),
        fakeHost = normalizeQuicFakeHost(config.fakeHost),
    )

private fun normalizeHostsConfig(config: RipDpiHostsConfig): RipDpiHostsConfig {
    val normalizedEntries = config.entries?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedMode = if (normalizedEntries == null) RipDpiHostsConfig.Mode.Disable else config.mode
    return RipDpiHostsConfig(
        mode = normalizedMode,
        entries = normalizedEntries.takeUnless { normalizedMode == RipDpiHostsConfig.Mode.Disable },
    )
}

private fun normalizeHostAutolearnConfig(config: RipDpiHostAutolearnConfig): RipDpiHostAutolearnConfig =
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
