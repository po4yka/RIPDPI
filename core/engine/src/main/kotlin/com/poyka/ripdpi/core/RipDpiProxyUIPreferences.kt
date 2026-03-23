package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.ActivationFilterModel
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlDelta
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlFallback
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlMax
import com.poyka.ripdpi.data.DefaultAdaptiveFakeTtlMin
import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.DefaultHostAutolearnMaxHosts
import com.poyka.ripdpi.data.DefaultHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.DefaultSplitMarker
import com.poyka.ripdpi.data.DefaultTlsRecordMarker
import com.poyka.ripdpi.data.FakePayloadProfileCompatDefault
import com.poyka.ripdpi.data.QuicFakeProfileDisabled
import com.poyka.ripdpi.data.QuicInitialModeRouteAndCache
import com.poyka.ripdpi.data.StrategyLaneFamilies
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
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
import com.poyka.ripdpi.data.normalizeUdpFakeProfile
import com.poyka.ripdpi.proto.AppSettings

data class RipDpiListenConfig(
    val ip: String = "127.0.0.1",
    val port: Int = 1080,
    val maxConnections: Int = 512,
    val bufferSize: Int = 16384,
    val tcpFastOpen: Boolean = false,
    val defaultTtl: Int = 0,
    val customTtl: Boolean = false,
)

data class RipDpiProtocolConfig(
    val resolveDomains: Boolean = true,
    val desyncHttp: Boolean = true,
    val desyncHttps: Boolean = true,
    val desyncUdp: Boolean = false,
)

data class RipDpiChainConfig(
    val groupActivationFilter: ActivationFilterModel = ActivationFilterModel(),
    val tcpSteps: List<TcpChainStepModel> =
        listOf(TcpChainStepModel(kind = TcpChainStepKind.Disorder, marker = DefaultSplitMarker)),
    val udpSteps: List<UdpChainStepModel> = emptyList(),
)

data class RipDpiFakePacketConfig(
    val fakeTtl: Int = 8,
    val adaptiveFakeTtlEnabled: Boolean = false,
    val adaptiveFakeTtlDelta: Int = DefaultAdaptiveFakeTtlDelta,
    val adaptiveFakeTtlMin: Int = DefaultAdaptiveFakeTtlMin,
    val adaptiveFakeTtlMax: Int = DefaultAdaptiveFakeTtlMax,
    val adaptiveFakeTtlFallback: Int = DefaultAdaptiveFakeTtlFallback,
    val fakeSni: String = DefaultFakeSni,
    val httpFakeProfile: String = FakePayloadProfileCompatDefault,
    val fakeTlsUseOriginal: Boolean = false,
    val fakeTlsRandomize: Boolean = false,
    val fakeTlsDupSessionId: Boolean = false,
    val fakeTlsPadEncap: Boolean = false,
    val fakeTlsSize: Int = 0,
    val fakeTlsSniMode: String = "fixed",
    val tlsFakeProfile: String = FakePayloadProfileCompatDefault,
    val udpFakeProfile: String = FakePayloadProfileCompatDefault,
    val fakeOffsetMarker: String = DefaultFakeOffsetMarker,
    val oobChar: Char = 'a',
    val dropSack: Boolean = false,
)

data class RipDpiParserEvasionConfig(
    val hostMixedCase: Boolean = false,
    val domainMixedCase: Boolean = false,
    val hostRemoveSpaces: Boolean = false,
    val httpMethodEol: Boolean = false,
    val httpUnixEol: Boolean = false,
)

data class RipDpiQuicConfig(
    val initialMode: String = QuicInitialModeRouteAndCache,
    val supportV1: Boolean = true,
    val supportV2: Boolean = true,
    val fakeProfile: String = QuicFakeProfileDisabled,
    val fakeHost: String = "",
)

data class RipDpiHostsConfig(
    val mode: Mode = Mode.Disable,
    val entries: String? = null,
) {
    enum class Mode {
        Disable,
        Blacklist,
        Whitelist,
        ;

        companion object {
            fun fromWireName(name: String): Mode =
                when (name) {
                    "disable" -> Disable
                    "blacklist" -> Blacklist
                    "whitelist" -> Whitelist
                    else -> throw IllegalArgumentException("Unknown hosts mode: $name")
                }
        }

        val wireName: String
            get() =
                when (this) {
                    Disable -> "disable"
                    Blacklist -> "blacklist"
                    Whitelist -> "whitelist"
                }
    }
}

data class RipDpiHostAutolearnConfig(
    val enabled: Boolean = false,
    val penaltyTtlHours: Int = DefaultHostAutolearnPenaltyTtlHours,
    val maxHosts: Int = DefaultHostAutolearnMaxHosts,
    val storePath: String? = null,
    val networkScopeKey: String? = null,
)

data class RipDpiWsTunnelConfig(
    val enabled: Boolean = false,
    val mode: String? = null,
)

class RipDpiProxyUIPreferences(
    listen: RipDpiListenConfig = RipDpiListenConfig(),
    protocols: RipDpiProtocolConfig = RipDpiProtocolConfig(),
    chains: RipDpiChainConfig = RipDpiChainConfig(),
    fakePackets: RipDpiFakePacketConfig = RipDpiFakePacketConfig(),
    parserEvasions: RipDpiParserEvasionConfig = RipDpiParserEvasionConfig(),
    quic: RipDpiQuicConfig = RipDpiQuicConfig(),
    hosts: RipDpiHostsConfig = RipDpiHostsConfig(),
    hostAutolearn: RipDpiHostAutolearnConfig = RipDpiHostAutolearnConfig(),
    wsTunnel: RipDpiWsTunnelConfig = RipDpiWsTunnelConfig(),
    nativeLogLevel: String? = null,
    runtimeContext: RipDpiRuntimeContext? = null,
) : RipDpiProxyPreferences {
    val listen: RipDpiListenConfig = normalizeListenConfig(listen)
    val protocols: RipDpiProtocolConfig = protocols
    val chains: RipDpiChainConfig = normalizeChainConfig(chains)
    val fakePackets: RipDpiFakePacketConfig = normalizeFakePacketConfig(fakePackets)
    val parserEvasions: RipDpiParserEvasionConfig = parserEvasions
    val quic: RipDpiQuicConfig = normalizeQuicConfig(quic)
    val hosts: RipDpiHostsConfig = normalizeHostsConfig(hosts)
    val hostAutolearn: RipDpiHostAutolearnConfig = normalizeHostAutolearnConfig(hostAutolearn)
    val wsTunnel: RipDpiWsTunnelConfig = wsTunnel
    val nativeLogLevel: String? = nativeLogLevel?.trim()?.takeIf { it.isNotEmpty() }
    val runtimeContext: RipDpiRuntimeContext? = normalizeRuntimeContext(runtimeContext)
    val chainSummary: String = formatChainSummary(this.chains.tcpSteps, this.chains.udpSteps)

    override fun toNativeConfigJson(): String = RipDpiProxyJsonCodec.encodeUiPreferences(this)

    fun withSessionOverrides(
        hostAutolearnStorePath: String? = hostAutolearn.storePath,
        networkScopeKey: String? = hostAutolearn.networkScopeKey,
        runtimeContext: RipDpiRuntimeContext? = this.runtimeContext,
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
        )

    companion object {
        fun fromSettings(
            settings: AppSettings,
            hostAutolearnStorePath: String? = null,
            networkScopeKey: String? = null,
            runtimeContext: RipDpiRuntimeContext? = null,
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
        desyncUdp = protocols.desyncUdp,
        quicInitialMode = quic.initialMode,
        quicFakeProfile = quic.fakeProfile,
        activeDns = activeDns,
    )

private fun normalizeListenConfig(config: RipDpiListenConfig): RipDpiListenConfig =
    config.copy(ip = config.ip.ifBlank { "127.0.0.1" })

private fun normalizeChainConfig(config: RipDpiChainConfig): RipDpiChainConfig =
    config.copy(
        groupActivationFilter = normalizeActivationFilter(config.groupActivationFilter),
        tcpSteps = config.tcpSteps.map(::normalizeTcpChainStep),
        udpSteps =
            config.udpSteps.map { step ->
                step.copy(activationFilter = normalizeActivationFilter(step.activationFilter))
            },
    )

private fun normalizeFakePacketConfig(config: RipDpiFakePacketConfig): RipDpiFakePacketConfig {
    val normalizedFakeTtl = config.fakeTtl
    val normalizedAdaptiveFakeTtlMin = normalizeAdaptiveFakeTtlMin(config.adaptiveFakeTtlMin)
    return config.copy(
        adaptiveFakeTtlDelta = normalizeAdaptiveFakeTtlDelta(config.adaptiveFakeTtlDelta),
        adaptiveFakeTtlMin = normalizedAdaptiveFakeTtlMin,
        adaptiveFakeTtlMax = normalizeAdaptiveFakeTtlMax(config.adaptiveFakeTtlMax, normalizedAdaptiveFakeTtlMin),
        adaptiveFakeTtlFallback =
            normalizeAdaptiveFakeTtlFallback(
                config.adaptiveFakeTtlFallback,
                normalizedFakeTtl.takeIf { it > 0 } ?: DefaultAdaptiveFakeTtlFallback,
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
        entries =
            if (normalizedMode ==
                RipDpiHostsConfig.Mode.Disable
            ) {
                null
            } else {
                normalizedEntries
            },
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
