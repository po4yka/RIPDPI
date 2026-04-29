package com.poyka.ripdpi.core

import com.poyka.ripdpi.core.codec.AdaptiveSectionCodec
import com.poyka.ripdpi.core.codec.ChainCodec
import com.poyka.ripdpi.core.codec.NativeAdaptiveFallbackConfig
import com.poyka.ripdpi.core.codec.NativeChainConfig
import com.poyka.ripdpi.core.codec.NativeFakePacketConfig
import com.poyka.ripdpi.core.codec.NativeHostAutolearnConfig
import com.poyka.ripdpi.core.codec.NativeHostsConfig
import com.poyka.ripdpi.core.codec.NativeListenConfig
import com.poyka.ripdpi.core.codec.NativeLogContext
import com.poyka.ripdpi.core.codec.NativeParserEvasionConfig
import com.poyka.ripdpi.core.codec.NativeProtocolConfig
import com.poyka.ripdpi.core.codec.NativeQuicConfig
import com.poyka.ripdpi.core.codec.NativeRelayConfig
import com.poyka.ripdpi.core.codec.NativeRuntimeContext
import com.poyka.ripdpi.core.codec.NativeSessionLocalProxyOverrides
import com.poyka.ripdpi.core.codec.NativeWarpConfig
import com.poyka.ripdpi.core.codec.NativeWsTunnelConfig
import com.poyka.ripdpi.core.codec.NetworkSectionCodec
import com.poyka.ripdpi.core.codec.PacketCodec
import com.poyka.ripdpi.core.codec.ProxyLogContextCodec
import com.poyka.ripdpi.core.codec.ProxyRuntimeContextCodec
import com.poyka.ripdpi.core.codec.RelaySectionCodec
import com.poyka.ripdpi.core.codec.SessionOverrideCodec
import com.poyka.ripdpi.core.codec.WarpSectionCodec
import com.poyka.ripdpi.core.codec.WsTunnelSectionCodec
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object RipDpiProxyJsonCodec {
    private val json =
        Json {
            classDiscriminator = "kind"
            encodeDefaults = true
        }
    private val groupedUiKeys =
        setOf(
            "listen",
            "protocols",
            "chains",
            "fakePackets",
            "parserEvasions",
            "adaptiveFallback",
            "quic",
            "hosts",
            "upstreamRelay",
            "warp",
            "hostAutolearn",
            "wsTunnel",
        )
    private val legacyFlatUiKeys =
        setOf(
            "ip",
            "port",
            "maxConnections",
            "bufferSize",
            "tcpFastOpen",
            "defaultTtl",
            "customTtl",
            "noDomain",
            "desyncHttp",
            "desyncHttps",
            "desyncUdp",
            "desyncMethod",
            "splitMarker",
            "tcpChainSteps",
            "groupActivationFilter",
            "splitPosition",
            "splitAtHost",
            "fakeTtl",
            "adaptiveFakeTtlEnabled",
            "adaptiveFakeTtlDelta",
            "adaptiveFakeTtlMin",
            "adaptiveFakeTtlMax",
            "adaptiveFakeTtlFallback",
            "fakeSni",
            "httpFakeProfile",
            "fakeTlsUseOriginal",
            "fakeTlsRandomize",
            "fakeTlsDupSessionId",
            "fakeTlsPadEncap",
            "fakeTlsSize",
            "fakeTlsSniMode",
            "tlsFakeProfile",
            "oobChar",
            "hostMixedCase",
            "domainMixedCase",
            "hostRemoveSpaces",
            "httpMethodEol",
            "httpMethodSpace",
            "httpUnixEol",
            "httpHostPad",
            "tlsRecordSplit",
            "tlsRecordSplitMarker",
            "tlsRecordSplitPosition",
            "tlsRecordSplitAtSni",
            "hostsMode",
            "udpFakeCount",
            "udpChainSteps",
            "udpFakeProfile",
            "dropSack",
            "fakeOffsetMarker",
            "fakeOffset",
            "quicInitialMode",
            "quicSupportV1",
            "quicSupportV2",
            "quicFakeProfile",
            "quicFakeHost",
            "hostAutolearnEnabled",
            "hostAutolearnPenaltyTtlSecs",
            "hostAutolearnPenaltyTtlHours",
            "hostAutolearnMaxHosts",
            "hostAutolearnStorePath",
            "networkScopeKey",
            "adaptiveFallbackEnabled",
            "adaptiveFallbackTorst",
            "adaptiveFallbackTlsErr",
            "adaptiveFallbackHttpRedirect",
            "adaptiveFallbackConnectFailure",
            "adaptiveFallbackAutoSort",
            "adaptiveFallbackCacheTtlSeconds",
            "adaptiveFallbackCachePrefixV4",
        )
    private const val LegacyCommandLineProgram = "cia" + "dpi"
    private const val LegacyStrategyPreset = "bye" + "dpi_default"

    fun encodeCommandLinePreferences(
        args: List<String>,
        hostAutolearnStorePath: String?,
        runtimeContext: RipDpiRuntimeContext?,
        logContext: RipDpiLogContext?,
        localListenPortOverride: Int? = null,
        localAuthToken: String? = null,
    ): String =
        encode(
            NativeProxyConfig.CommandLine(
                args = args,
                hostAutolearnStorePath = hostAutolearnStorePath,
                runtimeContext = ProxyRuntimeContextCodec.toNative(runtimeContext),
                logContext = ProxyLogContextCodec.toNative(logContext),
                sessionOverrides = SessionOverrideCodec.toNative(localListenPortOverride, localAuthToken),
            ),
        )

    fun encodeUiPreferences(
        preferences: RipDpiProxyUIPreferences,
        strategyPreset: String? = null,
        rootMode: Boolean = false,
        rootHelperSocketPath: String? = null,
        listenAuthToken: String? = null,
        localListenPortOverride: Int? = null,
        localAuthToken: String? = null,
        environmentKind: com.poyka.ripdpi.data.EnvironmentKind = com.poyka.ripdpi.data.EnvironmentKind.Unknown,
    ): String =
        encode(
            NativeProxyConfig.Ui(
                strategyPreset = strategyPreset,
                listen = NetworkSectionCodec.toNative(preferences.listen).copy(authToken = listenAuthToken),
                protocols = NetworkSectionCodec.toNative(preferences.protocols),
                chains = ChainCodec.toNative(preferences.chains),
                fakePackets = PacketCodec.toNative(preferences.fakePackets),
                parserEvasions = PacketCodec.toNative(preferences.parserEvasions),
                adaptiveFallback = AdaptiveSectionCodec.toNative(preferences.adaptiveFallback),
                quic = NetworkSectionCodec.toNative(preferences.quic),
                hosts = NetworkSectionCodec.toNative(preferences.hosts),
                upstreamRelay = RelaySectionCodec.toNative(preferences.relay),
                warp = WarpSectionCodec.toNative(preferences.warp),
                hostAutolearn = NetworkSectionCodec.toNative(preferences.hostAutolearn),
                wsTunnel = WsTunnelSectionCodec.toNative(preferences.wsTunnel),
                nativeLogLevel = preferences.nativeLogLevel,
                rootMode = rootMode,
                rootHelperSocketPath = rootHelperSocketPath,
                environmentKind = environmentKind.name,
                runtimeContext = ProxyRuntimeContextCodec.toNative(preferences.runtimeContext),
                logContext = ProxyLogContextCodec.toNative(preferences.logContext),
                sessionOverrides = SessionOverrideCodec.toNative(localListenPortOverride, localAuthToken),
            ),
        )

    fun decodeUiPreferences(configJson: String): RipDpiProxyUIPreferences? {
        val payload = decodeOrNull(configJson) as? NativeProxyConfig.Ui ?: return null
        return runCatching {
            RipDpiProxyUIPreferences(
                listen = NetworkSectionCodec.toModel(payload.listen),
                protocols = NetworkSectionCodec.toModel(payload.protocols),
                chains = ChainCodec.toModel(payload.chains),
                fakePackets = PacketCodec.toModel(payload.fakePackets),
                parserEvasions = PacketCodec.toModel(payload.parserEvasions),
                adaptiveFallback = AdaptiveSectionCodec.toModel(payload.adaptiveFallback),
                quic = NetworkSectionCodec.toModel(payload.quic),
                hosts = NetworkSectionCodec.toModel(payload.hosts),
                relay = RelaySectionCodec.toModel(payload.upstreamRelay),
                warp = WarpSectionCodec.toModel(payload.warp),
                hostAutolearn = NetworkSectionCodec.toModel(payload.hostAutolearn),
                wsTunnel = WsTunnelSectionCodec.toModel(payload.wsTunnel),
                nativeLogLevel = payload.nativeLogLevel,
                runtimeContext = ProxyRuntimeContextCodec.toModel(payload.runtimeContext),
                logContext = ProxyLogContextCodec.toModel(payload.logContext),
            )
        }.getOrNull()
    }

    fun stripRuntimeContext(configJson: String): String =
        when (val payload = decode(configJson)) {
            is NativeProxyConfig.CommandLine -> encode(payload.copy(runtimeContext = null, logContext = null))
            is NativeProxyConfig.Ui -> encode(payload.copy(runtimeContext = null, logContext = null))
        }

    fun rewriteJson(
        configJson: String,
        hostAutolearnStorePath: String?,
        networkScopeKey: String?,
        runtimeContext: RipDpiRuntimeContext?,
        logContext: RipDpiLogContext?,
        rootMode: Boolean = false,
        rootHelperSocketPath: String? = null,
        localListenPortOverride: Int? = null,
        localAuthToken: String? = null,
        environmentKind: com.poyka.ripdpi.data.EnvironmentKind = com.poyka.ripdpi.data.EnvironmentKind.Unknown,
    ): String =
        when (val payload = decode(configJson)) {
            is NativeProxyConfig.CommandLine -> {
                encode(
                    payload.copy(
                        runtimeContext = ProxyRuntimeContextCodec.toNative(runtimeContext) ?: payload.runtimeContext,
                        logContext = ProxyLogContextCodec.toNative(logContext) ?: payload.logContext,
                        sessionOverrides =
                            SessionOverrideCodec.merge(
                                existing = payload.sessionOverrides,
                                listenPortOverride = localListenPortOverride,
                                authToken = localAuthToken,
                            ),
                    ),
                )
            }

            is NativeProxyConfig.Ui -> {
                val preferences =
                    requireNotNull(decodeUiPreferences(configJson)) {
                        "Unable to decode proxy UI preferences"
                    }.withSessionOverrides(
                        hostAutolearnStorePath = hostAutolearnStorePath ?: payload.hostAutolearn.storePath,
                        networkScopeKey = networkScopeKey ?: payload.hostAutolearn.networkScopeKey,
                        runtimeContext = runtimeContext ?: ProxyRuntimeContextCodec.toModel(payload.runtimeContext),
                        logContext = logContext ?: ProxyLogContextCodec.toModel(payload.logContext),
                    )
                encodeUiPreferences(
                    preferences,
                    strategyPreset = payload.strategyPreset,
                    rootMode = rootMode,
                    rootHelperSocketPath = rootHelperSocketPath,
                    listenAuthToken = payload.listen.authToken,
                    localListenPortOverride = localListenPortOverride ?: payload.sessionOverrides?.listenPortOverride,
                    localAuthToken = localAuthToken ?: payload.sessionOverrides?.authToken,
                    environmentKind = environmentKind,
                )
            }
        }

    private fun decode(configJson: String): NativeProxyConfig {
        val element = json.parseToJsonElement(configJson)
        validateUiPayloadShape(element)
        return json
            .decodeFromString(NativeProxyConfig.serializer(), configJson)
            .also(::validateSupportedPayload)
    }

    private fun decodeOrNull(configJson: String): NativeProxyConfig? = runCatching { decode(configJson) }.getOrNull()

    private fun encode(payload: NativeProxyConfig): String =
        payload
            .also(::validateSupportedPayload)
            .let(json::encodeToString)

    private fun validateUiPayloadShape(element: JsonElement) {
        val payload = element as? JsonObject ?: return
        if (payload["kind"]?.jsonPrimitive?.contentOrNull != "ui") {
            return
        }
        require(payload.keys.none(legacyFlatUiKeys::contains)) {
            "Legacy flat UI config JSON is not supported"
        }
        require(payload.keys.any(groupedUiKeys::contains)) {
            "Grouped UI config JSON must include at least one nested section"
        }
    }

    private fun validateSupportedPayload(payload: NativeProxyConfig) {
        when (payload) {
            is NativeProxyConfig.CommandLine -> {
                require(payload.args.firstOrNull() != LegacyCommandLineProgram) {
                    "Legacy command-line executable alias is not supported"
                }
            }

            is NativeProxyConfig.Ui -> {
                require(payload.strategyPreset != LegacyStrategyPreset) {
                    "Legacy strategy preset alias is not supported"
                }
            }
        }
    }

    @Serializable
    private sealed interface NativeProxyConfig {
        @Serializable
        @SerialName("command_line")
        data class CommandLine(
            val args: List<String>,
            val hostAutolearnStorePath: String? = null,
            val runtimeContext: NativeRuntimeContext? = null,
            val logContext: NativeLogContext? = null,
            val sessionOverrides: NativeSessionLocalProxyOverrides? = null,
        ) : NativeProxyConfig

        @Serializable
        @SerialName("ui")
        data class Ui(
            val strategyPreset: String? = null,
            val listen: NativeListenConfig = NativeListenConfig(),
            val protocols: NativeProtocolConfig = NativeProtocolConfig(),
            val chains: NativeChainConfig = NativeChainConfig(),
            val fakePackets: NativeFakePacketConfig = NativeFakePacketConfig(),
            val parserEvasions: NativeParserEvasionConfig = NativeParserEvasionConfig(),
            val adaptiveFallback: NativeAdaptiveFallbackConfig = NativeAdaptiveFallbackConfig(),
            val quic: NativeQuicConfig = NativeQuicConfig(),
            val hosts: NativeHostsConfig = NativeHostsConfig(),
            val upstreamRelay: NativeRelayConfig = NativeRelayConfig(),
            val warp: NativeWarpConfig = NativeWarpConfig(),
            val hostAutolearn: NativeHostAutolearnConfig = NativeHostAutolearnConfig(),
            val wsTunnel: NativeWsTunnelConfig = NativeWsTunnelConfig(),
            @EncodeDefault(EncodeDefault.Mode.NEVER)
            val nativeLogLevel: String? = null,
            val rootMode: Boolean = false,
            @EncodeDefault(EncodeDefault.Mode.NEVER)
            val rootHelperSocketPath: String? = null,
            // Environment classification supplied by the platform-side
            // EnvironmentDetector (P4.4.5, ADR-011). Wire form is the
            // EnvironmentKind variant name ("Field" / "Emulator" /
            // "Unknown"); Rust parses it back into ripdpi_config::EnvironmentKind.
            val environmentKind: String = "Unknown",
            val runtimeContext: NativeRuntimeContext? = null,
            val logContext: NativeLogContext? = null,
            val sessionOverrides: NativeSessionLocalProxyOverrides? = null,
        ) : NativeProxyConfig
    }
}
