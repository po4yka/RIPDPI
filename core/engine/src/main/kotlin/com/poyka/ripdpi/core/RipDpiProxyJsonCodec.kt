package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.ActivationFilterModel
import com.poyka.ripdpi.data.CanonicalDefaultSplitMarker
import com.poyka.ripdpi.data.NumericRangeModel
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepKind
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.normalizeActivationFilter
import com.poyka.ripdpi.data.normalizeRelayCongestionControl
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
    ): String =
        encode(
            NativeProxyConfig.CommandLine(
                args = args,
                hostAutolearnStorePath = hostAutolearnStorePath,
                runtimeContext = ProxyRuntimeContextCodec.toNative(runtimeContext),
                logContext = ProxyLogContextCodec.toNative(logContext),
            ),
        )

    fun encodeUiPreferences(
        preferences: RipDpiProxyUIPreferences,
        strategyPreset: String? = null,
        rootMode: Boolean = false,
        rootHelperSocketPath: String? = null,
        localAuthToken: String? = null,
    ): String =
        encode(
            NativeProxyConfig.Ui(
                strategyPreset = strategyPreset,
                listen = ConfigSectionCodec.toNative(preferences.listen).copy(authToken = localAuthToken),
                protocols = ConfigSectionCodec.toNative(preferences.protocols),
                chains = ChainCodec.toNative(preferences.chains),
                fakePackets = PacketCodec.toNative(preferences.fakePackets),
                parserEvasions = PacketCodec.toNative(preferences.parserEvasions),
                adaptiveFallback = AdaptiveCodec.toNative(preferences.adaptiveFallback),
                quic = EndpointCodec.toNative(preferences.quic),
                hosts = EndpointCodec.toNative(preferences.hosts),
                upstreamRelay = RelayCodec.toNative(preferences.relay),
                warp = EndpointCodec.toNative(preferences.warp),
                hostAutolearn = EndpointCodec.toNative(preferences.hostAutolearn),
                wsTunnel = WsTunnelCodec.toNative(preferences.wsTunnel),
                nativeLogLevel = preferences.nativeLogLevel,
                rootMode = rootMode,
                rootHelperSocketPath = rootHelperSocketPath,
                runtimeContext = ProxyRuntimeContextCodec.toNative(preferences.runtimeContext),
                logContext = ProxyLogContextCodec.toNative(preferences.logContext),
            ),
        )

    fun decodeUiPreferences(configJson: String): RipDpiProxyUIPreferences? {
        val payload = decodeOrNull(configJson) as? NativeProxyConfig.Ui ?: return null
        return runCatching { EndpointCodec.toModel(payload) }.getOrNull()
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
        localAuthToken: String? = null,
    ): String =
        when (val payload = decode(configJson)) {
            is NativeProxyConfig.CommandLine -> {
                encode(
                    payload.copy(
                        runtimeContext = ProxyRuntimeContextCodec.toNative(runtimeContext) ?: payload.runtimeContext,
                        logContext = ProxyLogContextCodec.toNative(logContext) ?: payload.logContext,
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
                    localAuthToken = localAuthToken,
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
    private data class NativeNumericRange(
        val start: Long? = null,
        val end: Long? = null,
    )

    @Serializable
    private data class NativeActivationFilter(
        val round: NativeNumericRange? = null,
        val payloadSize: NativeNumericRange? = null,
        val streamBytes: NativeNumericRange? = null,
        val tcpHasTimestamp: Boolean? = null,
        val tcpHasEch: Boolean? = null,
        val tcpWindowBelow: Int? = null,
        val tcpMssBelow: Int? = null,
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
    private data class NativePreferredEdge(
        val ip: String,
        val transportKind: String,
        val ipVersion: String,
        val successCount: Int = 0,
        val failureCount: Int = 0,
        val lastValidatedAt: Long? = null,
        val lastFailedAt: Long? = null,
        val echCapable: Boolean = false,
        val cdnProvider: String? = null,
    )

    @Serializable
    private data class NativeRuntimeContext(
        val encryptedDns: NativeEncryptedDnsContext? = null,
        val protectPath: String? = null,
        val preferredEdges: Map<String, List<NativePreferredEdge>> = emptyMap(),
        val directPathCapabilities: List<NativeDirectPathCapability> = emptyList(),
        val morphPolicy: NativeMorphPolicy? = null,
    )

    @Serializable
    private data class NativeDirectPathCapability(
        val authority: String,
        val quicUsable: Boolean? = null,
        val udpUsable: Boolean? = null,
        val fallbackRequired: Boolean? = null,
        val repeatedHandshakeFailureClass: String? = null,
        val updatedAt: Long = 0L,
    )

    @Serializable
    private data class NativeMorphPolicy(
        val id: String,
        val firstFlightSizeMin: Int = 0,
        val firstFlightSizeMax: Int = 0,
        val paddingEnvelopeMin: Int = 0,
        val paddingEnvelopeMax: Int = 0,
        val entropyTargetPermil: Int = 0,
        val tcpBurstCadenceMs: List<Int> = emptyList(),
        val tlsBurstCadenceMs: List<Int> = emptyList(),
        val quicBurstProfile: String = "",
        val fakePacketShapeProfile: String = "",
    )

    @Serializable
    private data class NativeLogContext(
        val runtimeId: String? = null,
        val mode: String? = null,
        val policySignature: String? = null,
        val fingerprintHash: String? = null,
        val diagnosticsSessionId: String? = null,
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
        val authToken: String? = null,
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
        val fakeOrder: String = "",
        val fakeSeqMode: String = "",
        val overlapSize: Int = 0,
        val fakeMode: String = "",
        val fragmentCount: Int,
        val minFragmentSize: Int,
        val maxFragmentSize: Int,
        val activationFilter: NativeActivationFilter? = null,
        val ipv6ExtensionProfile: String = "none",
        val tcpFlagsSet: String = "",
        val tcpFlagsUnset: String = "",
        val tcpFlagsOrigSet: String = "",
        val tcpFlagsOrigUnset: String = "",
    )

    @Serializable
    private data class NativeUdpChainStep(
        val kind: String,
        val count: Int,
        val splitBytes: Int = 0,
        val activationFilter: NativeActivationFilter? = null,
        val ipv6ExtensionProfile: String = "none",
    )

    @Serializable
    private data class NativeTcpRotationCandidate(
        val tcpSteps: List<NativeTcpChainStep> = emptyList(),
    )

    @Serializable
    private data class NativeTcpRotationConfig(
        val fails: Int = 3,
        val retrans: Int = 3,
        val seq: Int = 65_536,
        val rst: Int = 1,
        val timeSecs: Long = 60,
        val candidates: List<NativeTcpRotationCandidate> = emptyList(),
    )

    @Serializable
    private data class NativeChainConfig(
        val groupActivationFilter: NativeActivationFilter? = null,
        val tcpSteps: List<NativeTcpChainStep> =
            listOf(
                NativeTcpChainStep(
                    kind = "split",
                    marker = CanonicalDefaultSplitMarker,
                    midhostMarker = "",
                    fakeHostTemplate = "",
                    fakeOrder = "",
                    fakeSeqMode = "",
                    overlapSize = 0,
                    fakeMode = "",
                    fragmentCount = 0,
                    minFragmentSize = 0,
                    maxFragmentSize = 0,
                    tcpFlagsSet = "",
                    tcpFlagsUnset = "",
                    tcpFlagsOrigSet = "",
                    tcpFlagsOrigUnset = "",
                ),
            ),
        val tcpRotation: NativeTcpRotationConfig? = null,
        val udpSteps: List<NativeUdpChainStep> = emptyList(),
        val anyProtocol: Boolean = false,
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
        val fakeTlsSource: String = "profile",
        val fakeTlsSecondaryProfile: String = "",
        val fakeTcpTimestampEnabled: Boolean = false,
        val fakeTcpTimestampDeltaTicks: Int = 0,
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
        val windowClamp: Int? = null,
        val wsizeWindow: Int? = null,
        val wsizeScale: Int? = null,
        val stripTimestamps: Boolean = false,
        val ipIdMode: String = "",
        val quicBindLowPort: Boolean = false,
        val quicMigrateAfterHandshake: Boolean = false,
        val entropyMode: String = "disabled",
        val entropyPaddingTargetPermil: Int = 3400,
        val entropyPaddingMax: Int = 256,
        val shannonEntropyTargetPermil: Int = 7920,
        val tlsFingerprintProfile: String = "chrome_stable",
    )

    @Serializable
    private data class NativeParserEvasionConfig(
        val hostMixedCase: Boolean = false,
        val domainMixedCase: Boolean = false,
        val hostRemoveSpaces: Boolean = false,
        val httpMethodEol: Boolean = false,
        val httpMethodSpace: Boolean = false,
        val httpUnixEol: Boolean = false,
        val httpHostPad: Boolean = false,
        val httpHostExtraSpace: Boolean = false,
        val httpHostTab: Boolean = false,
    )

    @Serializable
    private data class NativeAdaptiveFallbackConfig(
        val enabled: Boolean = true,
        val torst: Boolean = true,
        val tlsErr: Boolean = true,
        val httpRedirect: Boolean = true,
        val connectFailure: Boolean = true,
        val autoSort: Boolean = true,
        val cacheTtlSeconds: Int = 90,
        val cachePrefixV4: Int = 24,
        val strategyEvolution: Boolean = false,
        val evolutionEpsilon: Double = 0.1,
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
    private data class NativeWarpManualEndpointConfig(
        val host: String = "",
        val ipv4: String = "",
        val ipv6: String = "",
        val port: Int = 2408,
    )

    @Serializable
    private data class NativeWarpAmneziaConfig(
        val enabled: Boolean = false,
        val jc: Int = 0,
        val jmin: Int = 0,
        val jmax: Int = 0,
        val h1: Long = 0L,
        val h2: Long = 0L,
        val h3: Long = 0L,
        val h4: Long = 0L,
        val s1: Int = 0,
        val s2: Int = 0,
        val s3: Int = 0,
        val s4: Int = 0,
    )

    @Serializable
    private data class NativeWarpConfig(
        val enabled: Boolean = false,
        val routeMode: String = "off",
        val routeHosts: String = "",
        val builtInRulesEnabled: Boolean = true,
        val endpointSelectionMode: String = "automatic",
        val manualEndpoint: NativeWarpManualEndpointConfig = NativeWarpManualEndpointConfig(),
        val scannerEnabled: Boolean = true,
        val scannerParallelism: Int = 10,
        val scannerMaxRttMs: Int = 1500,
        val amneziaPreset: String = "off",
        val amnezia: NativeWarpAmneziaConfig = NativeWarpAmneziaConfig(),
        val localSocksHost: String = "127.0.0.1",
        val localSocksPort: Int = 11888,
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
    private data class NativeRelayFinalmaskConfig(
        val type: String = com.poyka.ripdpi.data.RelayFinalmaskTypeOff,
        val headerHex: String = "",
        val trailerHex: String = "",
        val randRange: String = "",
        val sudokuSeed: String = "",
        val fragmentPackets: Int = 0,
        val fragmentMinBytes: Int = 0,
        val fragmentMaxBytes: Int = 0,
    )

    @Serializable
    private data class NativeRelayConfig(
        val enabled: Boolean = false,
        val kind: String = "off",
        val profileId: String = "",
        val outboundBindIp: String = "",
        val server: String = "",
        val serverPort: Int = 443,
        val serverName: String = "",
        val realityPublicKey: String = "",
        val realityShortId: String = "",
        val vlessTransport: String = "reality_tcp",
        val xhttpPath: String = "",
        val xhttpHost: String = "",
        val cloudflareTunnelMode: String = com.poyka.ripdpi.data.RelayCloudflareTunnelModeConsumeExisting,
        val cloudflarePublishLocalOriginUrl: String = "",
        val cloudflareCredentialsRef: String = "",
        val chainEntryServer: String = "",
        val chainEntryPort: Int = 443,
        val chainEntryServerName: String = "",
        val chainEntryPublicKey: String = "",
        val chainEntryShortId: String = "",
        val chainEntryProfileId: String = "",
        val chainExitServer: String = "",
        val chainExitPort: Int = 443,
        val chainExitServerName: String = "",
        val chainExitPublicKey: String = "",
        val chainExitShortId: String = "",
        val chainExitProfileId: String = "",
        val masqueUrl: String = "",
        val masqueUseHttp2Fallback: Boolean = true,
        val masqueCloudflareGeohashEnabled: Boolean = false,
        val tuicZeroRtt: Boolean = false,
        val tuicCongestionControl: String = "bbr",
        val shadowTlsInnerProfileId: String = "",
        val naivePath: String = "",
        val localSocksHost: String = "127.0.0.1",
        val localSocksPort: Int = 11980,
        val udpEnabled: Boolean = false,
        val tcpFallbackEnabled: Boolean = true,
        val finalmask: NativeRelayFinalmaskConfig = NativeRelayFinalmaskConfig(),
    )

    @Serializable
    private sealed interface NativeProxyConfig {
        @Serializable
        @SerialName("command_line")
        data class CommandLine(
            val args: List<String>,
            val hostAutolearnStorePath: String? = null,
            val runtimeContext: NativeRuntimeContext? = null,
            val logContext: NativeLogContext? = null,
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
            val runtimeContext: NativeRuntimeContext? = null,
            val logContext: NativeLogContext? = null,
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
                    tcpHasTimestamp = value.tcpHasTimestamp,
                    tcpHasEch = value.tcpHasEch,
                    tcpWindowBelow = value.tcpWindowBelow,
                    tcpMssBelow = value.tcpMssBelow,
                ),
            )

        fun toNative(value: ActivationFilterModel): NativeActivationFilter? =
            normalizeActivationFilter(value).let { normalized ->
                val round = toNative(normalized.round)
                val payloadSize = toNative(normalized.payloadSize)
                val streamBytes = toNative(normalized.streamBytes)
                if (
                    round == null &&
                    payloadSize == null &&
                    streamBytes == null &&
                    normalized.tcpHasTimestamp == null &&
                    normalized.tcpHasEch == null &&
                    normalized.tcpWindowBelow == null &&
                    normalized.tcpMssBelow == null
                ) {
                    null
                } else {
                    NativeActivationFilter(
                        round = round,
                        payloadSize = payloadSize,
                        streamBytes = streamBytes,
                        tcpHasTimestamp = normalized.tcpHasTimestamp,
                        tcpHasEch = normalized.tcpHasEch,
                        tcpWindowBelow = normalized.tcpWindowBelow,
                        tcpMssBelow = normalized.tcpMssBelow,
                    )
                }
            }
    }

    private object ProxyRuntimeContextCodec {
        fun toModel(value: NativeRuntimeContext?): RipDpiRuntimeContext? =
            normalizeRuntimeContext(
                value?.let {
                    RipDpiRuntimeContext(
                        encryptedDns =
                            it.encryptedDns?.let { dns ->
                                RipDpiEncryptedDnsContext(
                                    resolverId = dns.resolverId,
                                    protocol = dns.protocol,
                                    host = dns.host,
                                    port = dns.port,
                                    tlsServerName = dns.tlsServerName,
                                    bootstrapIps = dns.bootstrapIps,
                                    dohUrl = dns.dohUrl,
                                    dnscryptProviderName = dns.dnscryptProviderName,
                                    dnscryptPublicKey = dns.dnscryptPublicKey,
                                )
                            },
                        protectPath = it.protectPath,
                        preferredEdges =
                            it.preferredEdges.mapValues { (_, candidates) ->
                                candidates.map { edge ->
                                    com.poyka.ripdpi.data.PreferredEdgeCandidate(
                                        ip = edge.ip,
                                        transportKind = edge.transportKind,
                                        ipVersion = edge.ipVersion,
                                        successCount = edge.successCount,
                                        failureCount = edge.failureCount,
                                        lastValidatedAt = edge.lastValidatedAt,
                                        lastFailedAt = edge.lastFailedAt,
                                        echCapable = edge.echCapable,
                                        cdnProvider = edge.cdnProvider,
                                    )
                                }
                            },
                        directPathCapabilities =
                            it.directPathCapabilities.map { capability ->
                                RipDpiDirectPathCapability(
                                    authority = capability.authority,
                                    quicUsable = capability.quicUsable,
                                    udpUsable = capability.udpUsable,
                                    fallbackRequired = capability.fallbackRequired,
                                    repeatedHandshakeFailureClass = capability.repeatedHandshakeFailureClass,
                                    updatedAt = capability.updatedAt,
                                )
                            },
                        morphPolicy =
                            it.morphPolicy?.let { policy ->
                                RipDpiMorphPolicy(
                                    id = policy.id,
                                    firstFlightSizeMin = policy.firstFlightSizeMin,
                                    firstFlightSizeMax = policy.firstFlightSizeMax,
                                    paddingEnvelopeMin = policy.paddingEnvelopeMin,
                                    paddingEnvelopeMax = policy.paddingEnvelopeMax,
                                    entropyTargetPermil = policy.entropyTargetPermil,
                                    tcpBurstCadenceMs = policy.tcpBurstCadenceMs,
                                    tlsBurstCadenceMs = policy.tlsBurstCadenceMs,
                                    quicBurstProfile = policy.quicBurstProfile,
                                    fakePacketShapeProfile = policy.fakePacketShapeProfile,
                                )
                            },
                    )
                },
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
                    protectPath = context.protectPath,
                    preferredEdges =
                        context.preferredEdges.mapValues { (_, candidates) ->
                            candidates.map { edge ->
                                NativePreferredEdge(
                                    ip = edge.ip,
                                    transportKind = edge.transportKind,
                                    ipVersion = edge.ipVersion,
                                    successCount = edge.successCount,
                                    failureCount = edge.failureCount,
                                    lastValidatedAt = edge.lastValidatedAt,
                                    lastFailedAt = edge.lastFailedAt,
                                    echCapable = edge.echCapable,
                                    cdnProvider = edge.cdnProvider,
                                )
                            }
                        },
                    directPathCapabilities =
                        context.directPathCapabilities.map { capability ->
                            NativeDirectPathCapability(
                                authority = capability.authority,
                                quicUsable = capability.quicUsable,
                                udpUsable = capability.udpUsable,
                                fallbackRequired = capability.fallbackRequired,
                                repeatedHandshakeFailureClass = capability.repeatedHandshakeFailureClass,
                                updatedAt = capability.updatedAt,
                            )
                        },
                    morphPolicy =
                        context.morphPolicy?.let { policy ->
                            NativeMorphPolicy(
                                id = policy.id,
                                firstFlightSizeMin = policy.firstFlightSizeMin,
                                firstFlightSizeMax = policy.firstFlightSizeMax,
                                paddingEnvelopeMin = policy.paddingEnvelopeMin,
                                paddingEnvelopeMax = policy.paddingEnvelopeMax,
                                entropyTargetPermil = policy.entropyTargetPermil,
                                tcpBurstCadenceMs = policy.tcpBurstCadenceMs,
                                tlsBurstCadenceMs = policy.tlsBurstCadenceMs,
                                quicBurstProfile = policy.quicBurstProfile,
                                fakePacketShapeProfile = policy.fakePacketShapeProfile,
                            )
                        },
                )
            }
    }

    private object ProxyLogContextCodec {
        fun toModel(value: NativeLogContext?): RipDpiLogContext? =
            normalizeLogContext(
                value?.let {
                    RipDpiLogContext(
                        runtimeId = it.runtimeId,
                        mode = it.mode,
                        policySignature = it.policySignature,
                        fingerprintHash = it.fingerprintHash,
                        diagnosticsSessionId = it.diagnosticsSessionId,
                    )
                },
            )

        fun toNative(value: RipDpiLogContext?): NativeLogContext? =
            normalizeLogContext(value)?.let {
                NativeLogContext(
                    runtimeId = it.runtimeId,
                    mode = it.mode,
                    policySignature = it.policySignature,
                    fingerprintHash = it.fingerprintHash,
                    diagnosticsSessionId = it.diagnosticsSessionId,
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
        private fun nativeTcpStepToModel(step: NativeTcpChainStep): TcpChainStepModel? {
            val kind = TcpChainStepKind.fromWireName(step.kind) ?: return null
            return TcpChainStepModel(
                kind = kind,
                marker = step.marker,
                midhostMarker = step.midhostMarker,
                fakeHostTemplate = step.fakeHostTemplate,
                fakeOrder = step.fakeOrder,
                fakeSeqMode = step.fakeSeqMode,
                overlapSize = step.overlapSize,
                fakeMode = step.fakeMode,
                fragmentCount = step.fragmentCount,
                minFragmentSize = step.minFragmentSize,
                maxFragmentSize = step.maxFragmentSize,
                activationFilter = step.activationFilter?.let(RangeCodec::toModel) ?: ActivationFilterModel(),
                ipv6ExtensionProfile = step.ipv6ExtensionProfile,
                tcpFlagsSet = step.tcpFlagsSet,
                tcpFlagsUnset = step.tcpFlagsUnset,
                tcpFlagsOrigSet = step.tcpFlagsOrigSet,
                tcpFlagsOrigUnset = step.tcpFlagsOrigUnset,
            )
        }

        private fun modelTcpStepToNative(stepModel: TcpChainStepModel): NativeTcpChainStep {
            val step = normalizeTcpChainStepModel(stepModel)
            return NativeTcpChainStep(
                kind = step.kind.wireName,
                marker = step.marker,
                midhostMarker = step.midhostMarker,
                fakeHostTemplate = step.fakeHostTemplate,
                fakeOrder = step.fakeOrder,
                fakeSeqMode = step.fakeSeqMode,
                overlapSize = step.overlapSize,
                fakeMode = step.fakeMode,
                fragmentCount = step.fragmentCount,
                minFragmentSize = step.minFragmentSize,
                maxFragmentSize = step.maxFragmentSize,
                activationFilter = RangeCodec.toNative(step.activationFilter),
                ipv6ExtensionProfile = step.ipv6ExtensionProfile,
                tcpFlagsSet = step.tcpFlagsSet,
                tcpFlagsUnset = step.tcpFlagsUnset,
                tcpFlagsOrigSet = step.tcpFlagsOrigSet,
                tcpFlagsOrigUnset = step.tcpFlagsOrigUnset,
            )
        }

        fun toModel(value: NativeChainConfig): RipDpiChainConfig =
            RipDpiChainConfig(
                groupActivationFilter =
                    value.groupActivationFilter?.let(RangeCodec::toModel) ?: ActivationFilterModel(),
                tcpSteps = value.tcpSteps.mapNotNull(::nativeTcpStepToModel),
                tcpRotation =
                    value.tcpRotation?.let { rotation ->
                        RipDpiTcpRotationConfig(
                            fails = rotation.fails,
                            retrans = rotation.retrans,
                            seq = rotation.seq,
                            rst = rotation.rst,
                            timeSecs = rotation.timeSecs,
                            candidates =
                                rotation.candidates.map { candidate ->
                                    RipDpiTcpRotationCandidateConfig(
                                        tcpSteps = candidate.tcpSteps.mapNotNull(::nativeTcpStepToModel),
                                    )
                                },
                        )
                    },
                udpSteps =
                    value.udpSteps.mapNotNull { step ->
                        val kind = UdpChainStepKind.fromWireName(step.kind) ?: return@mapNotNull null
                        UdpChainStepModel(
                            kind = kind,
                            count = step.count,
                            splitBytes = step.splitBytes,
                            activationFilter =
                                step.activationFilter?.let(RangeCodec::toModel) ?: ActivationFilterModel(),
                            ipv6ExtensionProfile = step.ipv6ExtensionProfile,
                        )
                    },
                anyProtocol = value.anyProtocol,
            )

        fun toNative(value: RipDpiChainConfig): NativeChainConfig =
            NativeChainConfig(
                groupActivationFilter = RangeCodec.toNative(value.groupActivationFilter),
                tcpSteps = value.tcpSteps.map(::modelTcpStepToNative),
                tcpRotation =
                    value.tcpRotation?.let { rotation ->
                        NativeTcpRotationConfig(
                            fails = rotation.fails,
                            retrans = rotation.retrans,
                            seq = rotation.seq,
                            rst = rotation.rst,
                            timeSecs = rotation.timeSecs,
                            candidates =
                                rotation.candidates.map { candidate ->
                                    NativeTcpRotationCandidate(
                                        tcpSteps = candidate.tcpSteps.map(::modelTcpStepToNative),
                                    )
                                },
                        )
                    },
                udpSteps =
                    value.udpSteps.map {
                        NativeUdpChainStep(
                            kind = it.kind.wireName,
                            count = it.count,
                            splitBytes = it.splitBytes,
                            activationFilter = RangeCodec.toNative(it.activationFilter),
                            ipv6ExtensionProfile = it.ipv6ExtensionProfile,
                        )
                    },
                anyProtocol = value.anyProtocol,
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
                fakeTlsSource = value.fakeTlsSource,
                fakeTlsSecondaryProfile = value.fakeTlsSecondaryProfile,
                fakeTcpTimestampEnabled = value.fakeTcpTimestampEnabled,
                fakeTcpTimestampDeltaTicks = value.fakeTcpTimestampDeltaTicks,
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
                windowClamp = value.windowClamp,
                wsizeWindow = value.wsizeWindow,
                wsizeScale = value.wsizeScale,
                stripTimestamps = value.stripTimestamps,
                ipIdMode = value.ipIdMode,
                quicBindLowPort = value.quicBindLowPort,
                quicMigrateAfterHandshake = value.quicMigrateAfterHandshake,
                entropyMode = value.entropyMode,
                entropyPaddingTargetPermil = value.entropyPaddingTargetPermil,
                entropyPaddingMax = value.entropyPaddingMax,
                shannonEntropyTargetPermil = value.shannonEntropyTargetPermil,
                tlsFingerprintProfile = value.tlsFingerprintProfile,
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
                fakeTlsSource = value.fakeTlsSource,
                fakeTlsSecondaryProfile = value.fakeTlsSecondaryProfile,
                fakeTcpTimestampEnabled = value.fakeTcpTimestampEnabled,
                fakeTcpTimestampDeltaTicks = value.fakeTcpTimestampDeltaTicks,
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
                windowClamp = value.windowClamp,
                wsizeWindow = value.wsizeWindow,
                wsizeScale = value.wsizeScale,
                stripTimestamps = value.stripTimestamps,
                ipIdMode = value.ipIdMode,
                quicBindLowPort = value.quicBindLowPort,
                quicMigrateAfterHandshake = value.quicMigrateAfterHandshake,
                entropyMode = value.entropyMode,
                entropyPaddingTargetPermil = value.entropyPaddingTargetPermil,
                entropyPaddingMax = value.entropyPaddingMax,
                shannonEntropyTargetPermil = value.shannonEntropyTargetPermil,
                tlsFingerprintProfile = value.tlsFingerprintProfile,
            )

        fun toModel(value: NativeParserEvasionConfig): RipDpiParserEvasionConfig =
            RipDpiParserEvasionConfig(
                hostMixedCase = value.hostMixedCase,
                domainMixedCase = value.domainMixedCase,
                hostRemoveSpaces = value.hostRemoveSpaces,
                httpMethodEol = value.httpMethodEol,
                httpMethodSpace = value.httpMethodSpace,
                httpUnixEol = value.httpUnixEol,
                httpHostPad = value.httpHostPad,
                httpHostExtraSpace = value.httpHostExtraSpace,
                httpHostTab = value.httpHostTab,
            )

        fun toNative(value: RipDpiParserEvasionConfig): NativeParserEvasionConfig =
            NativeParserEvasionConfig(
                hostMixedCase = value.hostMixedCase,
                domainMixedCase = value.domainMixedCase,
                hostRemoveSpaces = value.hostRemoveSpaces,
                httpMethodEol = value.httpMethodEol,
                httpMethodSpace = value.httpMethodSpace,
                httpUnixEol = value.httpUnixEol,
                httpHostPad = value.httpHostPad,
                httpHostExtraSpace = value.httpHostExtraSpace,
                httpHostTab = value.httpHostTab,
            )
    }

    private object AdaptiveCodec {
        fun toModel(value: NativeAdaptiveFallbackConfig): RipDpiAdaptiveFallbackConfig =
            RipDpiAdaptiveFallbackConfig(
                enabled = value.enabled,
                torst = value.torst,
                tlsErr = value.tlsErr,
                httpRedirect = value.httpRedirect,
                connectFailure = value.connectFailure,
                autoSort = value.autoSort,
                cacheTtlSeconds = value.cacheTtlSeconds,
                cachePrefixV4 = value.cachePrefixV4,
                strategyEvolution = value.strategyEvolution,
                evolutionEpsilon = value.evolutionEpsilon,
            )

        fun toNative(value: RipDpiAdaptiveFallbackConfig): NativeAdaptiveFallbackConfig =
            NativeAdaptiveFallbackConfig(
                enabled = value.enabled,
                torst = value.torst,
                tlsErr = value.tlsErr,
                httpRedirect = value.httpRedirect,
                connectFailure = value.connectFailure,
                autoSort = value.autoSort,
                cacheTtlSeconds = value.cacheTtlSeconds,
                cachePrefixV4 = value.cachePrefixV4,
                strategyEvolution = value.strategyEvolution,
                evolutionEpsilon = value.evolutionEpsilon,
            )
    }

    private object RelayCodec {
        fun toModel(value: NativeRelayConfig): RipDpiRelayConfig =
            RipDpiRelayConfig(
                enabled = value.enabled,
                kind = value.kind,
                profileId = value.profileId,
                outboundBindIp = value.outboundBindIp,
                server = value.server,
                serverPort = value.serverPort,
                serverName = value.serverName,
                realityPublicKey = value.realityPublicKey,
                realityShortId = value.realityShortId,
                vlessTransport = value.vlessTransport,
                xhttpPath = value.xhttpPath,
                xhttpHost = value.xhttpHost,
                cloudflareTunnelMode = value.cloudflareTunnelMode,
                cloudflarePublishLocalOriginUrl = value.cloudflarePublishLocalOriginUrl,
                cloudflareCredentialsRef = value.cloudflareCredentialsRef,
                chainEntryServer = value.chainEntryServer,
                chainEntryPort = value.chainEntryPort,
                chainEntryServerName = value.chainEntryServerName,
                chainEntryPublicKey = value.chainEntryPublicKey,
                chainEntryShortId = value.chainEntryShortId,
                chainEntryProfileId = value.chainEntryProfileId,
                chainExitServer = value.chainExitServer,
                chainExitPort = value.chainExitPort,
                chainExitServerName = value.chainExitServerName,
                chainExitPublicKey = value.chainExitPublicKey,
                chainExitShortId = value.chainExitShortId,
                chainExitProfileId = value.chainExitProfileId,
                masqueUrl = value.masqueUrl,
                masqueUseHttp2Fallback = value.masqueUseHttp2Fallback,
                masqueCloudflareGeohashEnabled = value.masqueCloudflareGeohashEnabled,
                tuicZeroRtt = value.tuicZeroRtt,
                tuicCongestionControl = normalizeRelayCongestionControl(value.tuicCongestionControl),
                shadowTlsInnerProfileId = value.shadowTlsInnerProfileId,
                naivePath = value.naivePath,
                localSocksHost = value.localSocksHost,
                localSocksPort = value.localSocksPort,
                udpEnabled = value.udpEnabled,
                tcpFallbackEnabled = value.tcpFallbackEnabled,
                finalmask =
                    RipDpiRelayFinalmaskConfig(
                        type = value.finalmask.type,
                        headerHex = value.finalmask.headerHex,
                        trailerHex = value.finalmask.trailerHex,
                        randRange = value.finalmask.randRange,
                        sudokuSeed = value.finalmask.sudokuSeed,
                        fragmentPackets = value.finalmask.fragmentPackets,
                        fragmentMinBytes = value.finalmask.fragmentMinBytes,
                        fragmentMaxBytes = value.finalmask.fragmentMaxBytes,
                    ),
            )

        fun toNative(value: RipDpiRelayConfig): NativeRelayConfig =
            NativeRelayConfig(
                enabled = value.enabled,
                kind = value.kind,
                profileId = value.profileId,
                outboundBindIp = value.outboundBindIp,
                server = value.server,
                serverPort = value.serverPort,
                serverName = value.serverName,
                realityPublicKey = value.realityPublicKey,
                realityShortId = value.realityShortId,
                vlessTransport = value.vlessTransport,
                xhttpPath = value.xhttpPath,
                xhttpHost = value.xhttpHost,
                cloudflareTunnelMode = value.cloudflareTunnelMode,
                cloudflarePublishLocalOriginUrl = value.cloudflarePublishLocalOriginUrl,
                cloudflareCredentialsRef = value.cloudflareCredentialsRef,
                chainEntryServer = value.chainEntryServer,
                chainEntryPort = value.chainEntryPort,
                chainEntryServerName = value.chainEntryServerName,
                chainEntryPublicKey = value.chainEntryPublicKey,
                chainEntryShortId = value.chainEntryShortId,
                chainEntryProfileId = value.chainEntryProfileId,
                chainExitServer = value.chainExitServer,
                chainExitPort = value.chainExitPort,
                chainExitServerName = value.chainExitServerName,
                chainExitPublicKey = value.chainExitPublicKey,
                chainExitShortId = value.chainExitShortId,
                chainExitProfileId = value.chainExitProfileId,
                masqueUrl = value.masqueUrl,
                masqueUseHttp2Fallback = value.masqueUseHttp2Fallback,
                masqueCloudflareGeohashEnabled = value.masqueCloudflareGeohashEnabled,
                tuicZeroRtt = value.tuicZeroRtt,
                tuicCongestionControl = normalizeRelayCongestionControl(value.tuicCongestionControl),
                shadowTlsInnerProfileId = value.shadowTlsInnerProfileId,
                naivePath = value.naivePath,
                localSocksHost = value.localSocksHost,
                localSocksPort = value.localSocksPort,
                udpEnabled = value.udpEnabled,
                tcpFallbackEnabled = value.tcpFallbackEnabled,
                finalmask =
                    NativeRelayFinalmaskConfig(
                        type = value.finalmask.type,
                        headerHex = value.finalmask.headerHex,
                        trailerHex = value.finalmask.trailerHex,
                        randRange = value.finalmask.randRange,
                        sudokuSeed = value.finalmask.sudokuSeed,
                        fragmentPackets = value.finalmask.fragmentPackets,
                        fragmentMinBytes = value.finalmask.fragmentMinBytes,
                        fragmentMaxBytes = value.finalmask.fragmentMaxBytes,
                    ),
            )
    }

    private object WsTunnelCodec {
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

        fun toModel(value: NativeWarpConfig): RipDpiWarpConfig =
            RipDpiWarpConfig(
                enabled = value.enabled,
                routeMode = value.routeMode,
                routeHosts = value.routeHosts,
                builtInRulesEnabled = value.builtInRulesEnabled,
                endpointSelectionMode = value.endpointSelectionMode,
                manualEndpoint =
                    RipDpiWarpManualEndpointConfig(
                        host = value.manualEndpoint.host,
                        ipv4 = value.manualEndpoint.ipv4,
                        ipv6 = value.manualEndpoint.ipv6,
                        port = value.manualEndpoint.port,
                    ),
                scannerEnabled = value.scannerEnabled,
                scannerParallelism = value.scannerParallelism,
                scannerMaxRttMs = value.scannerMaxRttMs,
                amneziaPreset = value.amneziaPreset,
                amnezia =
                    RipDpiWarpAmneziaConfig(
                        enabled = value.amnezia.enabled,
                        jc = value.amnezia.jc,
                        jmin = value.amnezia.jmin,
                        jmax = value.amnezia.jmax,
                        h1 = value.amnezia.h1,
                        h2 = value.amnezia.h2,
                        h3 = value.amnezia.h3,
                        h4 = value.amnezia.h4,
                        s1 = value.amnezia.s1,
                        s2 = value.amnezia.s2,
                        s3 = value.amnezia.s3,
                        s4 = value.amnezia.s4,
                    ),
                localSocksHost = value.localSocksHost,
                localSocksPort = value.localSocksPort,
            )

        fun toNative(value: RipDpiWarpConfig): NativeWarpConfig =
            NativeWarpConfig(
                enabled = value.enabled,
                routeMode = value.routeMode,
                routeHosts = value.routeHosts,
                builtInRulesEnabled = value.builtInRulesEnabled,
                endpointSelectionMode = value.endpointSelectionMode,
                manualEndpoint =
                    NativeWarpManualEndpointConfig(
                        host = value.manualEndpoint.host,
                        ipv4 = value.manualEndpoint.ipv4,
                        ipv6 = value.manualEndpoint.ipv6,
                        port = value.manualEndpoint.port,
                    ),
                scannerEnabled = value.scannerEnabled,
                scannerParallelism = value.scannerParallelism,
                scannerMaxRttMs = value.scannerMaxRttMs,
                amneziaPreset = value.amneziaPreset,
                amnezia =
                    NativeWarpAmneziaConfig(
                        enabled = value.amnezia.enabled,
                        jc = value.amnezia.jc,
                        jmin = value.amnezia.jmin,
                        jmax = value.amnezia.jmax,
                        h1 = value.amnezia.h1,
                        h2 = value.amnezia.h2,
                        h3 = value.amnezia.h3,
                        h4 = value.amnezia.h4,
                        s1 = value.amnezia.s1,
                        s2 = value.amnezia.s2,
                        s3 = value.amnezia.s3,
                        s4 = value.amnezia.s4,
                    ),
                localSocksHost = value.localSocksHost,
                localSocksPort = value.localSocksPort,
            )

        fun toModel(value: NativeProxyConfig.Ui): RipDpiProxyUIPreferences =
            RipDpiProxyUIPreferences(
                listen = ConfigSectionCodec.toModel(value.listen),
                protocols = ConfigSectionCodec.toModel(value.protocols),
                chains = ChainCodec.toModel(value.chains),
                fakePackets = PacketCodec.toModel(value.fakePackets),
                parserEvasions = PacketCodec.toModel(value.parserEvasions),
                adaptiveFallback = AdaptiveCodec.toModel(value.adaptiveFallback),
                quic = toModel(value.quic),
                hosts = toModel(value.hosts),
                relay = RelayCodec.toModel(value.upstreamRelay),
                warp = toModel(value.warp),
                hostAutolearn = toModel(value.hostAutolearn),
                wsTunnel = WsTunnelCodec.toModel(value.wsTunnel),
                nativeLogLevel = value.nativeLogLevel,
                runtimeContext = ProxyRuntimeContextCodec.toModel(value.runtimeContext),
                logContext = ProxyLogContextCodec.toModel(value.logContext),
            )
    }
}
