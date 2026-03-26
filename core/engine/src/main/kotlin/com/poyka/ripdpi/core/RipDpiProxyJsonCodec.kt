package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.ActivationFilterModel
import com.poyka.ripdpi.data.NumericRangeModel
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepKind
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.normalizeActivationFilter
import com.poyka.ripdpi.data.normalizeTcpChainStepModel
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
            "quic",
            "hosts",
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
            "httpUnixEol",
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
        )
    private const val LegacyCommandLineProgram = "cia" + "dpi"
    private const val CommandLineProgram = "ripdpi"
    private const val LegacyStrategyPreset = "bye" + "dpi_default"
    private const val StrategyPreset = "ripdpi_default"

    fun encodeCommandLinePreferences(
        args: List<String>,
        hostAutolearnStorePath: String?,
        runtimeContext: RipDpiRuntimeContext?,
    ): String =
        encode(
            NativeProxyConfig.CommandLine(
                args = args,
                hostAutolearnStorePath = hostAutolearnStorePath,
                runtimeContext = ProxyRuntimeContextCodec.toNative(runtimeContext),
            ),
        )

    fun encodeUiPreferences(
        preferences: RipDpiProxyUIPreferences,
        strategyPreset: String? = null,
    ): String =
        encode(
            NativeProxyConfig.Ui(
                strategyPreset = strategyPreset,
                listen = ConfigSectionCodec.toNative(preferences.listen),
                protocols = ConfigSectionCodec.toNative(preferences.protocols),
                chains = ChainCodec.toNative(preferences.chains),
                fakePackets = PacketCodec.toNative(preferences.fakePackets),
                parserEvasions = PacketCodec.toNative(preferences.parserEvasions),
                quic = EndpointCodec.toNative(preferences.quic),
                hosts = EndpointCodec.toNative(preferences.hosts),
                hostAutolearn = EndpointCodec.toNative(preferences.hostAutolearn),
                wsTunnel = EndpointCodec.toNative(preferences.wsTunnel),
                nativeLogLevel = preferences.nativeLogLevel,
                runtimeContext = ProxyRuntimeContextCodec.toNative(preferences.runtimeContext),
            ),
        )

    fun decodeUiPreferences(configJson: String): RipDpiProxyUIPreferences? {
        val payload = decodeOrNull(configJson) as? NativeProxyConfig.Ui ?: return null
        return runCatching { EndpointCodec.toModel(payload) }.getOrNull()
    }

    fun stripRuntimeContext(configJson: String): String =
        when (val payload = decode(configJson)) {
            is NativeProxyConfig.CommandLine -> encode(payload.copy(runtimeContext = null))
            is NativeProxyConfig.Ui -> encode(payload.copy(runtimeContext = null))
        }

    fun rewriteJson(
        configJson: String,
        hostAutolearnStorePath: String?,
        networkScopeKey: String?,
        runtimeContext: RipDpiRuntimeContext?,
    ): String =
        when (val payload = decode(configJson)) {
            is NativeProxyConfig.CommandLine -> {
                encode(
                    payload.copy(
                        runtimeContext = ProxyRuntimeContextCodec.toNative(runtimeContext) ?: payload.runtimeContext,
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
                    )
                encodeUiPreferences(preferences, strategyPreset = payload.strategyPreset)
            }
        }

    private fun decode(configJson: String): NativeProxyConfig {
        val element = json.parseToJsonElement(configJson)
        validateUiPayloadShape(element)
        return normalize(json.decodeFromString(NativeProxyConfig.serializer(), configJson))
    }

    private fun decodeOrNull(configJson: String): NativeProxyConfig? = runCatching { decode(configJson) }.getOrNull()

    private fun encode(payload: NativeProxyConfig): String = json.encodeToString(normalize(payload))

    private fun normalize(payload: NativeProxyConfig): NativeProxyConfig =
        when (payload) {
            is NativeProxyConfig.CommandLine ->
                payload.copy(args = normalizeCommandLineArgs(payload.args))

            is NativeProxyConfig.Ui ->
                payload.copy(strategyPreset = normalizeStrategyPreset(payload.strategyPreset))
        }

    private fun normalizeCommandLineArgs(args: List<String>): List<String> =
        when (args.firstOrNull()) {
            LegacyCommandLineProgram -> listOf(CommandLineProgram) + args.drop(1)
            else -> args
        }

    private fun normalizeStrategyPreset(strategyPreset: String?): String? =
        when (strategyPreset) {
            LegacyStrategyPreset -> StrategyPreset
            else -> strategyPreset
        }

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

    @Serializable
    private data class NativeNumericRange(
        val start: Long? = null,
        val end: Long? = null,
    )

    @Serializable
    private data class NativeActivationFilter(
        val round: NativeNumericRange? = null,
        val payloadSize: NativeNumericRange? = null,
        val streamBytes: NativeNumericRange? = null,
    )

    @Serializable
    private data class NativeEncryptedDnsContext(
        val resolverId: String? = null,
        val protocol: String,
        val host: String,
        val port: Int,
        val tlsServerName: String? = null,
        val bootstrapIps: List<String> = emptyList(),
        val dohUrl: String? = null,
        val dnscryptProviderName: String? = null,
        val dnscryptPublicKey: String? = null,
    )

    @Serializable
    private data class NativeRuntimeContext(
        val encryptedDns: NativeEncryptedDnsContext? = null,
    )

    @Serializable
    private data class NativeListenConfig(
        val ip: String = "127.0.0.1",
        val port: Int = 1080,
        val maxConnections: Int = 512,
        val bufferSize: Int = 16384,
        val tcpFastOpen: Boolean = false,
        val defaultTtl: Int = 0,
        val customTtl: Boolean = false,
        val freezeDetectionEnabled: Boolean = false,
    )

    @Serializable
    private data class NativeProtocolConfig(
        val resolveDomains: Boolean = true,
        val desyncHttp: Boolean = true,
        val desyncHttps: Boolean = true,
        val desyncUdp: Boolean = false,
    )

    @Serializable
    private data class NativeTcpChainStep(
        val kind: String,
        val marker: String,
        val midhostMarker: String,
        val fakeHostTemplate: String,
        val fragmentCount: Int,
        val minFragmentSize: Int,
        val maxFragmentSize: Int,
        val activationFilter: NativeActivationFilter? = null,
    )

    @Serializable
    private data class NativeUdpChainStep(
        val kind: String,
        val count: Int,
        val activationFilter: NativeActivationFilter? = null,
    )

    @Serializable
    private data class NativeChainConfig(
        val groupActivationFilter: NativeActivationFilter? = null,
        val tcpSteps: List<NativeTcpChainStep> =
            listOf(
                NativeTcpChainStep(
                    kind = "disorder",
                    marker = "1",
                    midhostMarker = "",
                    fakeHostTemplate = "",
                    fragmentCount = 0,
                    minFragmentSize = 0,
                    maxFragmentSize = 0,
                ),
            ),
        val udpSteps: List<NativeUdpChainStep> = emptyList(),
    )

    @Serializable
    private data class NativeFakePacketConfig(
        val fakeTtl: Int = 8,
        val adaptiveFakeTtlEnabled: Boolean = false,
        val adaptiveFakeTtlDelta: Int = -1,
        val adaptiveFakeTtlMin: Int = 3,
        val adaptiveFakeTtlMax: Int = 12,
        val adaptiveFakeTtlFallback: Int = 8,
        val fakeSni: String = "www.iana.org",
        val httpFakeProfile: String = "compat_default",
        val fakeTlsUseOriginal: Boolean = false,
        val fakeTlsRandomize: Boolean = false,
        val fakeTlsDupSessionId: Boolean = false,
        val fakeTlsPadEncap: Boolean = false,
        val fakeTlsSize: Int = 0,
        val fakeTlsSniMode: String = "fixed",
        val tlsFakeProfile: String = "compat_default",
        val udpFakeProfile: String = "compat_default",
        val fakeOffsetMarker: String = "0",
        val oobChar: Int = 'a'.code,
        val dropSack: Boolean = false,
    )

    @Serializable
    private data class NativeParserEvasionConfig(
        val hostMixedCase: Boolean = false,
        val domainMixedCase: Boolean = false,
        val hostRemoveSpaces: Boolean = false,
        val httpMethodEol: Boolean = false,
        val httpUnixEol: Boolean = false,
    )

    @Serializable
    private data class NativeQuicConfig(
        val initialMode: String = "route_and_cache",
        val supportV1: Boolean = true,
        val supportV2: Boolean = true,
        val fakeProfile: String = "disabled",
        val fakeHost: String = "",
    )

    @Serializable
    private data class NativeHostsConfig(
        val mode: String = "disable",
        val entries: String? = null,
    )

    @Serializable
    private data class NativeWsTunnelConfig(
        val enabled: Boolean = false,
        val mode: String? = null,
    )

    @Serializable
    private data class NativeHostAutolearnConfig(
        val enabled: Boolean = false,
        val penaltyTtlHours: Int = 6,
        val maxHosts: Int = 512,
        val storePath: String? = null,
        val networkScopeKey: String? = null,
    )

    @Serializable
    private sealed interface NativeProxyConfig {
        @Serializable
        @SerialName("command_line")
        data class CommandLine(
            val args: List<String>,
            val hostAutolearnStorePath: String? = null,
            val runtimeContext: NativeRuntimeContext? = null,
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
            val quic: NativeQuicConfig = NativeQuicConfig(),
            val hosts: NativeHostsConfig = NativeHostsConfig(),
            val hostAutolearn: NativeHostAutolearnConfig = NativeHostAutolearnConfig(),
            val wsTunnel: NativeWsTunnelConfig = NativeWsTunnelConfig(),
            @EncodeDefault(EncodeDefault.Mode.NEVER)
            val nativeLogLevel: String? = null,
            val runtimeContext: NativeRuntimeContext? = null,
        ) : NativeProxyConfig
    }

    private object RangeCodec {
        fun toModel(value: NativeNumericRange): NumericRangeModel =
            NumericRangeModel(
                start = value.start,
                end = value.end,
            )

        fun toNative(value: NumericRangeModel): NativeNumericRange? =
            if (value.start == null && value.end == null) {
                null
            } else {
                NativeNumericRange(start = value.start, end = value.end)
            }

        fun toModel(value: NativeActivationFilter): ActivationFilterModel =
            normalizeActivationFilter(
                ActivationFilterModel(
                    round = value.round?.let(::toModel) ?: NumericRangeModel(),
                    payloadSize = value.payloadSize?.let(::toModel) ?: NumericRangeModel(),
                    streamBytes = value.streamBytes?.let(::toModel) ?: NumericRangeModel(),
                ),
            )

        fun toNative(value: ActivationFilterModel): NativeActivationFilter? =
            normalizeActivationFilter(value).let { normalized ->
                val round = toNative(normalized.round)
                val payloadSize = toNative(normalized.payloadSize)
                val streamBytes = toNative(normalized.streamBytes)
                if (round == null && payloadSize == null && streamBytes == null) {
                    null
                } else {
                    NativeActivationFilter(round = round, payloadSize = payloadSize, streamBytes = streamBytes)
                }
            }
    }

    private object ProxyRuntimeContextCodec {
        fun toModel(value: NativeRuntimeContext?): RipDpiRuntimeContext? =
            normalizeRuntimeContext(
                RipDpiRuntimeContext(
                    encryptedDns =
                        value?.encryptedDns?.let {
                            RipDpiEncryptedDnsContext(
                                resolverId = it.resolverId,
                                protocol = it.protocol,
                                host = it.host,
                                port = it.port,
                                tlsServerName = it.tlsServerName,
                                bootstrapIps = it.bootstrapIps,
                                dohUrl = it.dohUrl,
                                dnscryptProviderName = it.dnscryptProviderName,
                                dnscryptPublicKey = it.dnscryptPublicKey,
                            )
                        },
                ),
            )

        fun toNative(value: RipDpiRuntimeContext?): NativeRuntimeContext? =
            normalizeRuntimeContext(value)?.let { context ->
                NativeRuntimeContext(
                    encryptedDns =
                        context.encryptedDns?.let {
                            NativeEncryptedDnsContext(
                                resolverId = it.resolverId,
                                protocol = it.protocol,
                                host = it.host,
                                port = it.port,
                                tlsServerName = it.tlsServerName,
                                bootstrapIps = it.bootstrapIps,
                                dohUrl = it.dohUrl,
                                dnscryptProviderName = it.dnscryptProviderName,
                                dnscryptPublicKey = it.dnscryptPublicKey,
                            )
                        },
                )
            }
    }

    private object ConfigSectionCodec {
        fun toModel(value: NativeListenConfig): RipDpiListenConfig =
            RipDpiListenConfig(
                ip = value.ip,
                port = value.port,
                maxConnections = value.maxConnections,
                bufferSize = value.bufferSize,
                tcpFastOpen = value.tcpFastOpen,
                defaultTtl = value.defaultTtl,
                customTtl = value.customTtl,
                freezeDetectionEnabled = value.freezeDetectionEnabled,
            )

        fun toNative(value: RipDpiListenConfig): NativeListenConfig =
            NativeListenConfig(
                ip = value.ip,
                port = value.port,
                maxConnections = value.maxConnections,
                bufferSize = value.bufferSize,
                tcpFastOpen = value.tcpFastOpen,
                defaultTtl = value.defaultTtl,
                customTtl = value.customTtl,
                freezeDetectionEnabled = value.freezeDetectionEnabled,
            )

        fun toModel(value: NativeProtocolConfig): RipDpiProtocolConfig =
            RipDpiProtocolConfig(
                resolveDomains = value.resolveDomains,
                desyncHttp = value.desyncHttp,
                desyncHttps = value.desyncHttps,
                desyncUdp = value.desyncUdp,
            )

        fun toNative(value: RipDpiProtocolConfig): NativeProtocolConfig =
            NativeProtocolConfig(
                resolveDomains = value.resolveDomains,
                desyncHttp = value.desyncHttp,
                desyncHttps = value.desyncHttps,
                desyncUdp = value.desyncUdp,
            )
    }

    private object ChainCodec {
        fun toModel(value: NativeChainConfig): RipDpiChainConfig =
            RipDpiChainConfig(
                groupActivationFilter =
                    value.groupActivationFilter?.let(RangeCodec::toModel) ?: ActivationFilterModel(),
                tcpSteps =
                    value.tcpSteps.mapNotNull { step ->
                        val kind = TcpChainStepKind.fromWireName(step.kind) ?: return@mapNotNull null
                        TcpChainStepModel(
                            kind = kind,
                            marker = step.marker,
                            midhostMarker = step.midhostMarker,
                            fakeHostTemplate = step.fakeHostTemplate,
                            fragmentCount = step.fragmentCount,
                            minFragmentSize = step.minFragmentSize,
                            maxFragmentSize = step.maxFragmentSize,
                            activationFilter =
                                step.activationFilter?.let(RangeCodec::toModel) ?: ActivationFilterModel(),
                        )
                    },
                udpSteps =
                    value.udpSteps.mapNotNull { step ->
                        val kind = UdpChainStepKind.fromWireName(step.kind) ?: return@mapNotNull null
                        UdpChainStepModel(
                            kind = kind,
                            count = step.count,
                            activationFilter =
                                step.activationFilter?.let(RangeCodec::toModel) ?: ActivationFilterModel(),
                        )
                    },
            )

        fun toNative(value: RipDpiChainConfig): NativeChainConfig =
            NativeChainConfig(
                groupActivationFilter = RangeCodec.toNative(value.groupActivationFilter),
                tcpSteps =
                    value.tcpSteps.map {
                        val step = normalizeTcpChainStepModel(it)
                        NativeTcpChainStep(
                            kind = step.kind.wireName,
                            marker = step.marker,
                            midhostMarker = step.midhostMarker,
                            fakeHostTemplate = step.fakeHostTemplate,
                            fragmentCount = step.fragmentCount,
                            minFragmentSize = step.minFragmentSize,
                            maxFragmentSize = step.maxFragmentSize,
                            activationFilter = RangeCodec.toNative(step.activationFilter),
                        )
                    },
                udpSteps =
                    value.udpSteps.map {
                        NativeUdpChainStep(
                            kind = it.kind.wireName,
                            count = it.count,
                            activationFilter = RangeCodec.toNative(it.activationFilter),
                        )
                    },
            )
    }

    private object PacketCodec {
        fun toModel(value: NativeFakePacketConfig): RipDpiFakePacketConfig =
            RipDpiFakePacketConfig(
                fakeTtl = value.fakeTtl,
                adaptiveFakeTtlEnabled = value.adaptiveFakeTtlEnabled,
                adaptiveFakeTtlDelta = value.adaptiveFakeTtlDelta,
                adaptiveFakeTtlMin = value.adaptiveFakeTtlMin,
                adaptiveFakeTtlMax = value.adaptiveFakeTtlMax,
                adaptiveFakeTtlFallback = value.adaptiveFakeTtlFallback,
                fakeSni = value.fakeSni,
                httpFakeProfile = value.httpFakeProfile,
                fakeTlsUseOriginal = value.fakeTlsUseOriginal,
                fakeTlsRandomize = value.fakeTlsRandomize,
                fakeTlsDupSessionId = value.fakeTlsDupSessionId,
                fakeTlsPadEncap = value.fakeTlsPadEncap,
                fakeTlsSize = value.fakeTlsSize,
                fakeTlsSniMode = value.fakeTlsSniMode,
                tlsFakeProfile = value.tlsFakeProfile,
                udpFakeProfile = value.udpFakeProfile,
                fakeOffsetMarker = value.fakeOffsetMarker,
                oobChar = value.oobChar.toChar(),
                dropSack = value.dropSack,
            )

        fun toNative(value: RipDpiFakePacketConfig): NativeFakePacketConfig =
            NativeFakePacketConfig(
                fakeTtl = value.fakeTtl,
                adaptiveFakeTtlEnabled = value.adaptiveFakeTtlEnabled,
                adaptiveFakeTtlDelta = value.adaptiveFakeTtlDelta,
                adaptiveFakeTtlMin = value.adaptiveFakeTtlMin,
                adaptiveFakeTtlMax = value.adaptiveFakeTtlMax,
                adaptiveFakeTtlFallback = value.adaptiveFakeTtlFallback,
                fakeSni = value.fakeSni,
                httpFakeProfile = value.httpFakeProfile,
                fakeTlsUseOriginal = value.fakeTlsUseOriginal,
                fakeTlsRandomize = value.fakeTlsRandomize,
                fakeTlsDupSessionId = value.fakeTlsDupSessionId,
                fakeTlsPadEncap = value.fakeTlsPadEncap,
                fakeTlsSize = value.fakeTlsSize,
                fakeTlsSniMode = value.fakeTlsSniMode,
                tlsFakeProfile = value.tlsFakeProfile,
                udpFakeProfile = value.udpFakeProfile,
                fakeOffsetMarker = value.fakeOffsetMarker,
                oobChar = value.oobChar.code,
                dropSack = value.dropSack,
            )

        fun toModel(value: NativeParserEvasionConfig): RipDpiParserEvasionConfig =
            RipDpiParserEvasionConfig(
                hostMixedCase = value.hostMixedCase,
                domainMixedCase = value.domainMixedCase,
                hostRemoveSpaces = value.hostRemoveSpaces,
                httpMethodEol = value.httpMethodEol,
                httpUnixEol = value.httpUnixEol,
            )

        fun toNative(value: RipDpiParserEvasionConfig): NativeParserEvasionConfig =
            NativeParserEvasionConfig(
                hostMixedCase = value.hostMixedCase,
                domainMixedCase = value.domainMixedCase,
                hostRemoveSpaces = value.hostRemoveSpaces,
                httpMethodEol = value.httpMethodEol,
                httpUnixEol = value.httpUnixEol,
            )
    }

    private object EndpointCodec {
        fun toModel(value: NativeQuicConfig): RipDpiQuicConfig =
            RipDpiQuicConfig(
                initialMode = value.initialMode,
                supportV1 = value.supportV1,
                supportV2 = value.supportV2,
                fakeProfile = value.fakeProfile,
                fakeHost = value.fakeHost,
            )

        fun toNative(value: RipDpiQuicConfig): NativeQuicConfig =
            NativeQuicConfig(
                initialMode = value.initialMode,
                supportV1 = value.supportV1,
                supportV2 = value.supportV2,
                fakeProfile = value.fakeProfile,
                fakeHost = value.fakeHost,
            )

        fun toModel(value: NativeHostsConfig): RipDpiHostsConfig =
            RipDpiHostsConfig(
                mode = RipDpiHostsConfig.Mode.fromWireName(value.mode),
                entries = value.entries,
            )

        fun toNative(value: RipDpiHostsConfig): NativeHostsConfig =
            NativeHostsConfig(
                mode = value.mode.wireName,
                entries = value.entries,
            )

        fun toModel(value: NativeHostAutolearnConfig): RipDpiHostAutolearnConfig =
            RipDpiHostAutolearnConfig(
                enabled = value.enabled,
                penaltyTtlHours = value.penaltyTtlHours,
                maxHosts = value.maxHosts,
                storePath = value.storePath,
                networkScopeKey = value.networkScopeKey,
            )

        fun toNative(value: RipDpiHostAutolearnConfig): NativeHostAutolearnConfig =
            NativeHostAutolearnConfig(
                enabled = value.enabled,
                penaltyTtlHours = value.penaltyTtlHours,
                maxHosts = value.maxHosts,
                storePath = value.storePath,
                networkScopeKey = value.networkScopeKey,
            )

        fun toModel(value: NativeWsTunnelConfig): RipDpiWsTunnelConfig =
            RipDpiWsTunnelConfig(
                enabled = value.enabled,
                mode = value.mode,
            )

        fun toNative(value: RipDpiWsTunnelConfig): NativeWsTunnelConfig =
            NativeWsTunnelConfig(
                enabled = value.enabled,
                mode = value.mode,
            )

        fun toModel(value: NativeProxyConfig.Ui): RipDpiProxyUIPreferences =
            RipDpiProxyUIPreferences(
                listen = ConfigSectionCodec.toModel(value.listen),
                protocols = ConfigSectionCodec.toModel(value.protocols),
                chains = ChainCodec.toModel(value.chains),
                fakePackets = PacketCodec.toModel(value.fakePackets),
                parserEvasions = PacketCodec.toModel(value.parserEvasions),
                quic = toModel(value.quic),
                hosts = toModel(value.hosts),
                hostAutolearn = toModel(value.hostAutolearn),
                wsTunnel = toModel(value.wsTunnel),
                nativeLogLevel = value.nativeLogLevel,
                runtimeContext = ProxyRuntimeContextCodec.toModel(value.runtimeContext),
            )
    }
}
