package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.ActivationFilterModel
import com.poyka.ripdpi.data.NumericRangeModel
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepKind
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.normalizeActivationFilter
import com.poyka.ripdpi.data.normalizeTcpChainStepModel
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

    fun encodeCommandLinePreferences(
        args: List<String>,
        runtimeContext: RipDpiRuntimeContext?,
    ): String =
        encode(
            NativeProxyConfig.CommandLine(
                args = args,
                runtimeContext = runtimeContext.toNative(),
            ),
        )

    fun encodeUiPreferences(
        preferences: RipDpiProxyUIPreferences,
        strategyPreset: String? = null,
    ): String =
        encode(
            NativeProxyConfig.Ui(
                strategyPreset = strategyPreset,
                listen = preferences.listen.toNative(),
                protocols = preferences.protocols.toNative(),
                chains = preferences.chains.toNative(),
                fakePackets = preferences.fakePackets.toNative(),
                parserEvasions = preferences.parserEvasions.toNative(),
                quic = preferences.quic.toNative(),
                hosts = preferences.hosts.toNative(),
                hostAutolearn = preferences.hostAutolearn.toNative(),
                runtimeContext = preferences.runtimeContext.toNative(),
            ),
        )

    fun decodeUiPreferences(configJson: String): RipDpiProxyUIPreferences? {
        val payload = decodeOrNull(configJson) as? NativeProxyConfig.Ui ?: return null
        return runCatching { payload.toModel() }.getOrNull()
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
            is NativeProxyConfig.CommandLine ->
                encode(
                    payload.copy(
                        runtimeContext = runtimeContext.toNative() ?: payload.runtimeContext,
                    ),
                )

            is NativeProxyConfig.Ui -> {
                val preferences =
                    requireNotNull(decodeUiPreferences(configJson)) {
                        "Unable to decode proxy UI preferences"
                    }.withSessionOverrides(
                        hostAutolearnStorePath = hostAutolearnStorePath ?: payload.hostAutolearn.storePath,
                        networkScopeKey = networkScopeKey ?: payload.hostAutolearn.networkScopeKey,
                        runtimeContext = runtimeContext ?: payload.runtimeContext.toModel(),
                    )
                encodeUiPreferences(preferences, strategyPreset = payload.strategyPreset)
            }
        }

    private fun decode(configJson: String): NativeProxyConfig {
        val element = json.parseToJsonElement(configJson)
        validateUiPayloadShape(element)
        return json.decodeFromJsonElement(NativeProxyConfig.serializer(), element)
    }

    private fun decodeOrNull(configJson: String): NativeProxyConfig? = runCatching { decode(configJson) }.getOrNull()

    private fun encode(payload: NativeProxyConfig): String = json.encodeToString(payload)

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
            val runtimeContext: NativeRuntimeContext? = null,
        ) : NativeProxyConfig
    }

    private fun NativeNumericRange.toModel(): NumericRangeModel = NumericRangeModel(start = start, end = end)

    private fun NumericRangeModel.toNative(): NativeNumericRange? =
        if (start == null && end == null) {
            null
        } else {
            NativeNumericRange(start = start, end = end)
        }

    private fun NativeActivationFilter.toModel(): ActivationFilterModel =
        normalizeActivationFilter(
            ActivationFilterModel(
                round = round?.toModel() ?: NumericRangeModel(),
                payloadSize = payloadSize?.toModel() ?: NumericRangeModel(),
                streamBytes = streamBytes?.toModel() ?: NumericRangeModel(),
            ),
        )

    private fun ActivationFilterModel.toNative(): NativeActivationFilter? =
        normalizeActivationFilter(this).let { normalized ->
            val round = normalized.round.toNative()
            val payloadSize = normalized.payloadSize.toNative()
            val streamBytes = normalized.streamBytes.toNative()
            if (round == null && payloadSize == null && streamBytes == null) {
                null
            } else {
                NativeActivationFilter(round = round, payloadSize = payloadSize, streamBytes = streamBytes)
            }
        }

    private fun NativeRuntimeContext?.toModel(): RipDpiRuntimeContext? =
        normalizeRuntimeContext(
            RipDpiRuntimeContext(
                encryptedDns =
                    this?.encryptedDns?.let { value ->
                        RipDpiEncryptedDnsContext(
                            resolverId = value.resolverId,
                            protocol = value.protocol,
                            host = value.host,
                            port = value.port,
                            tlsServerName = value.tlsServerName,
                            bootstrapIps = value.bootstrapIps,
                            dohUrl = value.dohUrl,
                            dnscryptProviderName = value.dnscryptProviderName,
                            dnscryptPublicKey = value.dnscryptPublicKey,
                        )
                    },
            ),
        )

    private fun RipDpiRuntimeContext?.toNative(): NativeRuntimeContext? =
        normalizeRuntimeContext(this)?.let { context ->
            NativeRuntimeContext(
                encryptedDns =
                    context.encryptedDns?.let { value ->
                        NativeEncryptedDnsContext(
                            resolverId = value.resolverId,
                            protocol = value.protocol,
                            host = value.host,
                            port = value.port,
                            tlsServerName = value.tlsServerName,
                            bootstrapIps = value.bootstrapIps,
                            dohUrl = value.dohUrl,
                            dnscryptProviderName = value.dnscryptProviderName,
                            dnscryptPublicKey = value.dnscryptPublicKey,
                        )
                    },
            )
        }

    private fun NativeListenConfig.toModel(): RipDpiListenConfig =
        RipDpiListenConfig(
            ip = ip,
            port = port,
            maxConnections = maxConnections,
            bufferSize = bufferSize,
            tcpFastOpen = tcpFastOpen,
            defaultTtl = defaultTtl,
            customTtl = customTtl,
        )

    private fun RipDpiListenConfig.toNative(): NativeListenConfig =
        NativeListenConfig(
            ip = ip,
            port = port,
            maxConnections = maxConnections,
            bufferSize = bufferSize,
            tcpFastOpen = tcpFastOpen,
            defaultTtl = defaultTtl,
            customTtl = customTtl,
        )

    private fun NativeProtocolConfig.toModel(): RipDpiProtocolConfig =
        RipDpiProtocolConfig(
            resolveDomains = resolveDomains,
            desyncHttp = desyncHttp,
            desyncHttps = desyncHttps,
            desyncUdp = desyncUdp,
        )

    private fun RipDpiProtocolConfig.toNative(): NativeProtocolConfig =
        NativeProtocolConfig(
            resolveDomains = resolveDomains,
            desyncHttp = desyncHttp,
            desyncHttps = desyncHttps,
            desyncUdp = desyncUdp,
        )

    private fun NativeChainConfig.toModel(): RipDpiChainConfig =
        RipDpiChainConfig(
            groupActivationFilter = groupActivationFilter?.toModel() ?: ActivationFilterModel(),
            tcpSteps =
                tcpSteps.mapNotNull { step ->
                    val kind = TcpChainStepKind.fromWireName(step.kind) ?: return@mapNotNull null
                    TcpChainStepModel(
                        kind = kind,
                        marker = step.marker,
                        midhostMarker = step.midhostMarker,
                        fakeHostTemplate = step.fakeHostTemplate,
                        fragmentCount = step.fragmentCount,
                        minFragmentSize = step.minFragmentSize,
                        maxFragmentSize = step.maxFragmentSize,
                        activationFilter = step.activationFilter?.toModel() ?: ActivationFilterModel(),
                    )
                },
            udpSteps =
                udpSteps.mapNotNull { step ->
                    val kind = UdpChainStepKind.fromWireName(step.kind) ?: return@mapNotNull null
                    UdpChainStepModel(
                        kind = kind,
                        count = step.count,
                        activationFilter = step.activationFilter?.toModel() ?: ActivationFilterModel(),
                    )
                },
        )

    private fun RipDpiChainConfig.toNative(): NativeChainConfig =
        NativeChainConfig(
            groupActivationFilter = groupActivationFilter.toNative(),
            tcpSteps =
                tcpSteps.map {
                    val step = normalizeTcpChainStepModel(it)
                    NativeTcpChainStep(
                        kind = step.kind.wireName,
                        marker = step.marker,
                        midhostMarker = step.midhostMarker,
                        fakeHostTemplate = step.fakeHostTemplate,
                        fragmentCount = step.fragmentCount,
                        minFragmentSize = step.minFragmentSize,
                        maxFragmentSize = step.maxFragmentSize,
                        activationFilter = step.activationFilter.toNative(),
                    )
                },
            udpSteps =
                udpSteps.map {
                    NativeUdpChainStep(
                        kind = it.kind.wireName,
                        count = it.count,
                        activationFilter = it.activationFilter.toNative(),
                    )
                },
        )

    private fun NativeFakePacketConfig.toModel(): RipDpiFakePacketConfig =
        RipDpiFakePacketConfig(
            fakeTtl = fakeTtl,
            adaptiveFakeTtlEnabled = adaptiveFakeTtlEnabled,
            adaptiveFakeTtlDelta = adaptiveFakeTtlDelta,
            adaptiveFakeTtlMin = adaptiveFakeTtlMin,
            adaptiveFakeTtlMax = adaptiveFakeTtlMax,
            adaptiveFakeTtlFallback = adaptiveFakeTtlFallback,
            fakeSni = fakeSni,
            httpFakeProfile = httpFakeProfile,
            fakeTlsUseOriginal = fakeTlsUseOriginal,
            fakeTlsRandomize = fakeTlsRandomize,
            fakeTlsDupSessionId = fakeTlsDupSessionId,
            fakeTlsPadEncap = fakeTlsPadEncap,
            fakeTlsSize = fakeTlsSize,
            fakeTlsSniMode = fakeTlsSniMode,
            tlsFakeProfile = tlsFakeProfile,
            udpFakeProfile = udpFakeProfile,
            fakeOffsetMarker = fakeOffsetMarker,
            oobChar = oobChar.toChar(),
            dropSack = dropSack,
        )

    private fun RipDpiFakePacketConfig.toNative(): NativeFakePacketConfig =
        NativeFakePacketConfig(
            fakeTtl = fakeTtl,
            adaptiveFakeTtlEnabled = adaptiveFakeTtlEnabled,
            adaptiveFakeTtlDelta = adaptiveFakeTtlDelta,
            adaptiveFakeTtlMin = adaptiveFakeTtlMin,
            adaptiveFakeTtlMax = adaptiveFakeTtlMax,
            adaptiveFakeTtlFallback = adaptiveFakeTtlFallback,
            fakeSni = fakeSni,
            httpFakeProfile = httpFakeProfile,
            fakeTlsUseOriginal = fakeTlsUseOriginal,
            fakeTlsRandomize = fakeTlsRandomize,
            fakeTlsDupSessionId = fakeTlsDupSessionId,
            fakeTlsPadEncap = fakeTlsPadEncap,
            fakeTlsSize = fakeTlsSize,
            fakeTlsSniMode = fakeTlsSniMode,
            tlsFakeProfile = tlsFakeProfile,
            udpFakeProfile = udpFakeProfile,
            fakeOffsetMarker = fakeOffsetMarker,
            oobChar = oobChar.code,
            dropSack = dropSack,
        )

    private fun NativeParserEvasionConfig.toModel(): RipDpiParserEvasionConfig =
        RipDpiParserEvasionConfig(
            hostMixedCase = hostMixedCase,
            domainMixedCase = domainMixedCase,
            hostRemoveSpaces = hostRemoveSpaces,
            httpMethodEol = httpMethodEol,
            httpUnixEol = httpUnixEol,
        )

    private fun RipDpiParserEvasionConfig.toNative(): NativeParserEvasionConfig =
        NativeParserEvasionConfig(
            hostMixedCase = hostMixedCase,
            domainMixedCase = domainMixedCase,
            hostRemoveSpaces = hostRemoveSpaces,
            httpMethodEol = httpMethodEol,
            httpUnixEol = httpUnixEol,
        )

    private fun NativeQuicConfig.toModel(): RipDpiQuicConfig =
        RipDpiQuicConfig(
            initialMode = initialMode,
            supportV1 = supportV1,
            supportV2 = supportV2,
            fakeProfile = fakeProfile,
            fakeHost = fakeHost,
        )

    private fun RipDpiQuicConfig.toNative(): NativeQuicConfig =
        NativeQuicConfig(
            initialMode = initialMode,
            supportV1 = supportV1,
            supportV2 = supportV2,
            fakeProfile = fakeProfile,
            fakeHost = fakeHost,
        )

    private fun NativeHostsConfig.toModel(): RipDpiHostsConfig =
        RipDpiHostsConfig(
            mode = RipDpiHostsConfig.Mode.fromWireName(mode),
            entries = entries,
        )

    private fun RipDpiHostsConfig.toNative(): NativeHostsConfig =
        NativeHostsConfig(
            mode = mode.wireName,
            entries = entries,
        )

    private fun NativeHostAutolearnConfig.toModel(): RipDpiHostAutolearnConfig =
        RipDpiHostAutolearnConfig(
            enabled = enabled,
            penaltyTtlHours = penaltyTtlHours,
            maxHosts = maxHosts,
            storePath = storePath,
            networkScopeKey = networkScopeKey,
        )

    private fun RipDpiHostAutolearnConfig.toNative(): NativeHostAutolearnConfig =
        NativeHostAutolearnConfig(
            enabled = enabled,
            penaltyTtlHours = penaltyTtlHours,
            maxHosts = maxHosts,
            storePath = storePath,
            networkScopeKey = networkScopeKey,
        )

    private fun NativeProxyConfig.Ui.toModel(): RipDpiProxyUIPreferences =
        RipDpiProxyUIPreferences(
            listen = listen.toModel(),
            protocols = protocols.toModel(),
            chains = chains.toModel(),
            fakePackets = fakePackets.toModel(),
            parserEvasions = parserEvasions.toModel(),
            quic = quic.toModel(),
            hosts = hosts.toModel(),
            hostAutolearn = hostAutolearn.toModel(),
            runtimeContext = runtimeContext.toModel(),
        )
}
