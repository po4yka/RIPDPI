package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.routing.DestinationRoutingPolicy
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.EnvironmentKind
import com.poyka.ripdpi.data.StrategyLaneFamilies
import com.poyka.ripdpi.data.awg.AwgActivationRequest
import com.poyka.ripdpi.data.deriveStrategyLaneFamilies
import com.poyka.ripdpi.data.formatChainSummary
import com.poyka.ripdpi.data.toSettingsSections
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
    geoipDbPath: String? = null,
    geositeDbPath: String? = null,
    val environmentKind: EnvironmentKind = EnvironmentKind.Unknown,
    val destinationRouting: DestinationRoutingPolicy = DestinationRoutingPolicy(canonicalDigest = ""),
    /**
     * AmneziaWG VPN-mode egress request. Non-null means AWG is the configured egress
     * transport for this session; null means AWG is not active. AWG and WARP are
     * mutually exclusive — when this is non-null, [warp] must not be started
     * simultaneously. Consumed via [com.poyka.ripdpi.core.awgConfigOrNull].
     */
    val awg: AwgActivationRequest? = null,
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
    val geoipDbPath: String? = geoipDbPath?.trim()?.takeIf { it.isNotEmpty() }
    val geositeDbPath: String? = geositeDbPath?.trim()?.takeIf { it.isNotEmpty() }
    val chainSummary: String = formatChainSummary(this.chains.tcpSteps, this.chains.udpSteps)

    override fun toNativeConfigJson(): String =
        RipDpiProxyJsonCodec.encodeUiPreferences(
            NativeProxyCreateRequest(
                preferences = this,
                rootMode = rootMode,
                rootHelperSocketPath = rootHelperSocketPath,
                geoipDbPath = geoipDbPath,
                geositeDbPath = geositeDbPath,
                environmentKind = environmentKind,
            ),
        )

    fun withSessionOverrides(
        hostAutolearn: RipDpiHostAutolearnConfig = this.hostAutolearn,
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
            geoipDbPath = geoipDbPath,
            geositeDbPath = geositeDbPath,
            environmentKind = environmentKind,
            destinationRouting = destinationRouting,
            awg = awg,
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
            geoipDbPath: String? = null,
            geositeDbPath: String? = null,
            environmentKind: EnvironmentKind = EnvironmentKind.Unknown,
            destinationRouting: DestinationRoutingPolicy = DestinationRoutingPolicy(canonicalDigest = ""),
            awg: AwgActivationRequest? = null,
        ): RipDpiProxyUIPreferences =
            RipDpiProxyUIPreferences(
                listen = buildListenConfig(settings.toSettingsSections().proxy),
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
                geoipDbPath = geoipDbPath,
                geositeDbPath = geositeDbPath,
                environmentKind = environmentKind,
                destinationRouting = destinationRouting,
                awg = awg,
            )
    }
}

fun RipDpiProxyUIPreferences.withConnectionConcurrencyPolicy(
    policy: RipDpiConnectionConcurrencyPolicy,
): RipDpiProxyUIPreferences =
    RipDpiProxyUIPreferences(
        listen = listen,
        protocols = protocols,
        chains = chains,
        fakePackets = fakePackets.copy(tlsFingerprintProfile = policy.selectedProfileId),
        parserEvasions = parserEvasions,
        adaptiveFallback = adaptiveFallback,
        quic = quic,
        hosts = hosts,
        relay = relay,
        warp = warp,
        hostAutolearn = hostAutolearn,
        wsTunnel = wsTunnel,
        nativeLogLevel = nativeLogLevel,
        runtimeContext = (runtimeContext ?: RipDpiRuntimeContext()).copy(connectionConcurrency = policy),
        logContext = logContext,
        rootMode = rootMode,
        rootHelperSocketPath = rootHelperSocketPath,
        geoipDbPath = geoipDbPath,
        geositeDbPath = geositeDbPath,
        environmentKind = environmentKind,
        destinationRouting = destinationRouting,
        awg = awg,
    )

/** Replaces the destination-route snapshot while preserving every other runtime preference. */
fun RipDpiProxyUIPreferences.withDestinationRoutingPolicy(
    policy: DestinationRoutingPolicy,
    awgOverride: AwgActivationRequest? = awg,
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
        hostAutolearn = hostAutolearn,
        wsTunnel = wsTunnel,
        nativeLogLevel = nativeLogLevel,
        runtimeContext = runtimeContext,
        logContext = logContext,
        rootMode = rootMode,
        rootHelperSocketPath = rootHelperSocketPath,
        geoipDbPath = geoipDbPath,
        geositeDbPath = geositeDbPath,
        environmentKind = environmentKind,
        destinationRouting = policy,
        awg = awgOverride,
    )

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

internal const val MaxValidPortNumber = 65535
