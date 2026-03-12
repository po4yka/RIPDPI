package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.ActivationFilterModel
import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.DefaultHostAutolearnMaxHosts
import com.poyka.ripdpi.data.DefaultHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.FakePayloadProfileCompatDefault
import com.poyka.ripdpi.data.DefaultSplitMarker
import com.poyka.ripdpi.data.DefaultTlsRecordMarker
import com.poyka.ripdpi.data.effectiveFakeTlsSniMode
import com.poyka.ripdpi.data.effectiveHttpFakeProfile
import com.poyka.ripdpi.data.effectiveQuicFakeHost
import com.poyka.ripdpi.data.effectiveQuicFakeProfile
import com.poyka.ripdpi.data.effectiveTlsFakeProfile
import com.poyka.ripdpi.data.QuicInitialModeRouteAndCache
import com.poyka.ripdpi.data.QuicFakeProfileDisabled
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepKind
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.effectiveUdpFakeProfile
import com.poyka.ripdpi.data.effectiveFakeOffsetMarker
import com.poyka.ripdpi.data.effectiveQuicInitialMode
import com.poyka.ripdpi.data.effectiveQuicSupportV1
import com.poyka.ripdpi.data.effectiveQuicSupportV2
import com.poyka.ripdpi.data.effectiveGroupActivationFilter
import com.poyka.ripdpi.data.effectiveSplitMarker
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveTlsRecordMarker
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.formatChainSummary
import com.poyka.ripdpi.data.normalizeHostAutolearnMaxHosts
import com.poyka.ripdpi.data.normalizeHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.normalizeFakeTlsSniMode
import com.poyka.ripdpi.data.normalizeHttpFakeProfile
import com.poyka.ripdpi.data.normalizeActivationFilter
import com.poyka.ripdpi.data.normalizeQuicFakeHost
import com.poyka.ripdpi.data.normalizeQuicFakeProfile
import com.poyka.ripdpi.data.normalizeTlsFakeProfile
import com.poyka.ripdpi.data.NumericRangeModel
import com.poyka.ripdpi.data.isTlsPrelude
import com.poyka.ripdpi.data.normalizeTcpChainStepModel
import com.poyka.ripdpi.data.normalizeQuicInitialMode
import com.poyka.ripdpi.data.normalizeOffsetExpression
import com.poyka.ripdpi.data.normalizeUdpFakeProfile
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.utility.shellSplit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val NativeProxyJson =
    Json {
        classDiscriminator = "kind"
    }

sealed interface RipDpiProxyPreferences {
    fun toNativeConfigJson(): String
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

private fun NativeNumericRange.toModel(): NumericRangeModel =
    NumericRangeModel(start = start, end = end)

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

fun decodeRipDpiProxyUiPreferences(configJson: String): RipDpiProxyUIPreferences? {
    val payload = runCatching { NativeProxyJson.decodeFromString<NativeProxyConfig>(configJson) }.getOrNull()
    val ui = payload as? NativeProxyConfig.Ui ?: return null
    return RipDpiProxyUIPreferences(
        ip = ui.ip,
        port = ui.port,
        maxConnections = ui.maxConnections,
        bufferSize = ui.bufferSize,
        defaultTtl = if (ui.customTtl) ui.defaultTtl else null,
        noDomain = ui.noDomain,
        desyncHttp = ui.desyncHttp,
        desyncHttps = ui.desyncHttps,
        desyncUdp = ui.desyncUdp,
        desyncMethod = RipDpiProxyUIPreferences.DesyncMethod.fromName(ui.desyncMethod),
        splitMarker = ui.splitMarker,
        groupActivationFilter = ui.groupActivationFilter?.toModel(),
        tcpChainSteps =
            ui.tcpChainSteps.mapNotNull { step ->
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
        fakeTtl = ui.fakeTtl,
        fakeSni = ui.fakeSni,
        httpFakeProfile = ui.httpFakeProfile,
        fakeTlsUseOriginal = ui.fakeTlsUseOriginal,
        fakeTlsRandomize = ui.fakeTlsRandomize,
        fakeTlsDupSessionId = ui.fakeTlsDupSessionId,
        fakeTlsPadEncap = ui.fakeTlsPadEncap,
        fakeTlsSize = ui.fakeTlsSize,
        fakeTlsSniMode = ui.fakeTlsSniMode,
        tlsFakeProfile = ui.tlsFakeProfile,
        oobChar = ui.oobChar.toChar().toString(),
        hostMixedCase = ui.hostMixedCase,
        domainMixedCase = ui.domainMixedCase,
        hostRemoveSpaces = ui.hostRemoveSpaces,
        tlsRecordSplit = ui.tlsRecordSplit,
        tlsRecordSplitMarker = ui.tlsRecordSplitMarker,
        hostsMode = RipDpiProxyUIPreferences.HostsMode.fromName(ui.hostsMode),
        hosts = ui.hosts,
        tcpFastOpen = ui.tcpFastOpen,
        udpFakeCount = ui.udpFakeCount,
        udpFakeProfile = ui.udpFakeProfile,
        dropSack = ui.dropSack,
        fakeOffsetMarker = ui.fakeOffsetMarker,
        udpChainSteps =
            ui.udpChainSteps.mapNotNull { step ->
                val kind = UdpChainStepKind.fromWireName(step.kind) ?: return@mapNotNull null
                UdpChainStepModel(
                    kind = kind,
                    count = step.count,
                    activationFilter = step.activationFilter?.toModel() ?: ActivationFilterModel(),
                )
            },
        quicInitialMode = ui.quicInitialMode,
        quicSupportV1 = ui.quicSupportV1,
        quicSupportV2 = ui.quicSupportV2,
        quicFakeProfile = ui.quicFakeProfile,
        quicFakeHost = ui.quicFakeHost,
        hostAutolearnEnabled = ui.hostAutolearnEnabled,
        hostAutolearnPenaltyTtlHours = ui.hostAutolearnPenaltyTtlSecs / 3600,
        hostAutolearnMaxHosts = ui.hostAutolearnMaxHosts,
        hostAutolearnStorePath = ui.hostAutolearnStorePath,
    )
}

class RipDpiProxyCmdPreferences(
    val args: Array<String>,
) : RipDpiProxyPreferences {
    constructor(cmd: String) : this(cmdToArgs(cmd))

    companion object {
        private fun cmdToArgs(cmd: String): Array<String> {
            val firstArgIndex = cmd.indexOf("-")
            val argsStr = (if (firstArgIndex > 0) cmd.substring(firstArgIndex) else cmd).trim()
            return arrayOf("ciadpi") + shellSplit(argsStr)
        }
    }

    override fun toNativeConfigJson(): String =
        NativeProxyJson.encodeToString<NativeProxyConfig>(
            NativeProxyConfig.CommandLine(
                args = args.toList(),
            ),
        )
}

class RipDpiProxyUIPreferences(
    ip: String? = null,
    port: Int? = null,
    maxConnections: Int? = null,
    bufferSize: Int? = null,
    defaultTtl: Int? = null,
    noDomain: Boolean? = null,
    desyncHttp: Boolean? = null,
    desyncHttps: Boolean? = null,
    desyncUdp: Boolean? = null,
    desyncMethod: DesyncMethod? = null,
    splitMarker: String? = null,
    groupActivationFilter: ActivationFilterModel? = null,
    tcpChainSteps: List<TcpChainStepModel>? = null,
    fakeTtl: Int? = null,
    fakeSni: String? = null,
    httpFakeProfile: String? = null,
    fakeTlsUseOriginal: Boolean? = null,
    fakeTlsRandomize: Boolean? = null,
    fakeTlsDupSessionId: Boolean? = null,
    fakeTlsPadEncap: Boolean? = null,
    fakeTlsSize: Int? = null,
    fakeTlsSniMode: String? = null,
    tlsFakeProfile: String? = null,
    oobChar: String? = null,
    hostMixedCase: Boolean? = null,
    domainMixedCase: Boolean? = null,
    hostRemoveSpaces: Boolean? = null,
    tlsRecordSplit: Boolean? = null,
    tlsRecordSplitMarker: String? = null,
    hostsMode: HostsMode? = null,
    hosts: String? = null,
    tcpFastOpen: Boolean? = null,
    udpFakeCount: Int? = null,
    udpFakeProfile: String? = null,
    dropSack: Boolean? = null,
    fakeOffsetMarker: String? = null,
    udpChainSteps: List<UdpChainStepModel>? = null,
    quicInitialMode: String? = null,
    quicSupportV1: Boolean? = null,
    quicSupportV2: Boolean? = null,
    quicFakeProfile: String? = null,
    quicFakeHost: String? = null,
    hostAutolearnEnabled: Boolean? = null,
    hostAutolearnPenaltyTtlHours: Int? = null,
    hostAutolearnMaxHosts: Int? = null,
    hostAutolearnStorePath: String? = null,
) : RipDpiProxyPreferences {
    val ip: String = ip ?: "127.0.0.1"
    val port: Int = port ?: 1080
    val maxConnections: Int = maxConnections ?: 512
    val bufferSize: Int = bufferSize ?: 16384
    val defaultTtl: Int = defaultTtl ?: 0
    val customTtl: Boolean = defaultTtl != null
    val noDomain: Boolean = noDomain ?: false
    val desyncHttp: Boolean = desyncHttp ?: true
    val desyncHttps: Boolean = desyncHttps ?: true
    val desyncUdp: Boolean = desyncUdp ?: false
    val desyncMethod: DesyncMethod = desyncMethod ?: DesyncMethod.Disorder
    val splitMarker: String = normalizeOffsetExpression(splitMarker.orEmpty(), DefaultSplitMarker)
    val groupActivationFilter: ActivationFilterModel = normalizeActivationFilter(groupActivationFilter ?: ActivationFilterModel())
    val tcpChainSteps: List<TcpChainStepModel> =
        tcpChainSteps?.map(::normalizeTcpChainStep)
            ?: buildLegacyTcpChain(
                desyncMethod = this.desyncMethod,
                splitMarker = this.splitMarker,
                tlsRecordSplit = tlsRecordSplit ?: false,
                tlsRecordSplitMarker = tlsRecordSplitMarker,
            )
    val fakeTtl: Int = fakeTtl ?: 8
    val fakeSni: String = fakeSni ?: DefaultFakeSni
    val httpFakeProfile: String = normalizeHttpFakeProfile(httpFakeProfile.orEmpty().ifBlank { FakePayloadProfileCompatDefault })
    val fakeTlsUseOriginal: Boolean = fakeTlsUseOriginal ?: false
    val fakeTlsRandomize: Boolean = fakeTlsRandomize ?: false
    val fakeTlsDupSessionId: Boolean = fakeTlsDupSessionId ?: false
    val fakeTlsPadEncap: Boolean = fakeTlsPadEncap ?: false
    val fakeTlsSize: Int = fakeTlsSize ?: 0
    val fakeTlsSniMode: String = normalizeFakeTlsSniMode(fakeTlsSniMode.orEmpty())
    val tlsFakeProfile: String = normalizeTlsFakeProfile(tlsFakeProfile.orEmpty().ifBlank { FakePayloadProfileCompatDefault })
    val oobChar: Byte = (oobChar ?: "a")[0].code.toByte()
    val hostMixedCase: Boolean = hostMixedCase ?: false
    val domainMixedCase: Boolean = domainMixedCase ?: false
    val hostRemoveSpaces: Boolean = hostRemoveSpaces ?: false
    val tlsRecordSplit: Boolean = tlsRecordSplit ?: false
    val tlsRecordSplitMarker: String = normalizeOffsetExpression(tlsRecordSplitMarker.orEmpty(), DefaultTlsRecordMarker)
    val hostsMode: HostsMode =
        if (hosts?.isBlank() != false) {
            HostsMode.Disable
        } else {
            hostsMode ?: HostsMode.Disable
        }
    val hosts: String? =
        if (this.hostsMode == HostsMode.Disable) {
            null
        } else {
            hosts?.trim()
        }
    val tcpFastOpen: Boolean = tcpFastOpen ?: false
    val udpFakeCount: Int = udpFakeCount ?: 0
    val udpChainSteps: List<UdpChainStepModel> = udpChainSteps ?: buildLegacyUdpChain(this.udpFakeCount)
    val udpFakeProfile: String = normalizeUdpFakeProfile(udpFakeProfile.orEmpty().ifBlank { FakePayloadProfileCompatDefault })
    val dropSack: Boolean = dropSack ?: false
    val fakeOffsetMarker: String = normalizeOffsetExpression(fakeOffsetMarker.orEmpty(), DefaultFakeOffsetMarker)
    val quicInitialMode: String = normalizeQuicInitialMode(quicInitialMode.orEmpty().ifBlank { QuicInitialModeRouteAndCache })
    val quicSupportV1: Boolean = quicSupportV1 ?: true
    val quicSupportV2: Boolean = quicSupportV2 ?: true
    val quicFakeProfile: String = normalizeQuicFakeProfile(quicFakeProfile.orEmpty().ifBlank { QuicFakeProfileDisabled })
    val quicFakeHost: String = normalizeQuicFakeHost(quicFakeHost.orEmpty())
    val hostAutolearnEnabled: Boolean = hostAutolearnEnabled ?: false
    val hostAutolearnPenaltyTtlHours: Int =
        normalizeHostAutolearnPenaltyTtlHours(hostAutolearnPenaltyTtlHours ?: DefaultHostAutolearnPenaltyTtlHours)
    val hostAutolearnMaxHosts: Int =
        normalizeHostAutolearnMaxHosts(hostAutolearnMaxHosts ?: DefaultHostAutolearnMaxHosts)
    val hostAutolearnStorePath: String? =
        hostAutolearnStorePath
            ?.trim()
            ?.takeIf { it.isNotEmpty() && this.hostAutolearnEnabled }
    val chainSummary: String = formatChainSummary(this.tcpChainSteps, this.udpChainSteps)

    constructor(
        settings: AppSettings,
        hostAutolearnStorePath: String? = null,
    ) : this(
        ip = settings.proxyIp.ifEmpty { null },
        port = settings.proxyPort.takeIf { it > 0 },
        maxConnections = settings.maxConnections.takeIf { it > 0 },
        bufferSize = settings.bufferSize.takeIf { it > 0 },
        defaultTtl = if (settings.customTtl) settings.defaultTtl else null,
        noDomain = settings.noDomain,
        desyncHttp = settings.desyncHttp,
        desyncHttps = settings.desyncHttps,
        desyncUdp = settings.desyncUdp,
        desyncMethod =
            settings.desyncMethod
                .ifEmpty { null }
                ?.let { DesyncMethod.fromName(it) },
        splitMarker = settings.effectiveSplitMarker(),
        groupActivationFilter = settings.effectiveGroupActivationFilter(),
        tcpChainSteps = settings.effectiveTcpChainSteps(),
        fakeTtl = settings.fakeTtl.takeIf { it > 0 },
        fakeSni = settings.fakeSni.ifEmpty { null },
        httpFakeProfile = settings.effectiveHttpFakeProfile(),
        fakeTlsUseOriginal = settings.fakeTlsUseOriginal,
        fakeTlsRandomize = settings.fakeTlsRandomize,
        fakeTlsDupSessionId = settings.fakeTlsDupSessionId,
        fakeTlsPadEncap = settings.fakeTlsPadEncap,
        fakeTlsSize = settings.fakeTlsSize,
        fakeTlsSniMode = settings.effectiveFakeTlsSniMode(),
        tlsFakeProfile = settings.effectiveTlsFakeProfile(),
        oobChar = settings.oobData.ifEmpty { null },
        hostMixedCase = settings.hostMixedCase,
        domainMixedCase = settings.domainMixedCase,
        hostRemoveSpaces = settings.hostRemoveSpaces,
        tlsRecordSplit = settings.tlsrecEnabled,
        tlsRecordSplitMarker = settings.effectiveTlsRecordMarker(),
        hostsMode =
            settings.hostsMode
                .ifEmpty { null }
                ?.let { HostsMode.fromName(it) },
        hosts =
            when {
                settings.hostsMode == "blacklist" -> settings.hostsBlacklist
                settings.hostsMode == "whitelist" -> settings.hostsWhitelist
                else -> null
            },
        tcpFastOpen = settings.tcpFastOpen,
        udpFakeCount = settings.udpFakeCount,
        udpFakeProfile = settings.effectiveUdpFakeProfile(),
        dropSack = settings.dropSack,
        fakeOffsetMarker = settings.effectiveFakeOffsetMarker(),
        udpChainSteps = settings.effectiveUdpChainSteps(),
        quicInitialMode = settings.effectiveQuicInitialMode(),
        quicSupportV1 = settings.effectiveQuicSupportV1(),
        quicSupportV2 = settings.effectiveQuicSupportV2(),
        quicFakeProfile = settings.effectiveQuicFakeProfile(),
        quicFakeHost = settings.effectiveQuicFakeHost(),
        hostAutolearnEnabled = settings.hostAutolearnEnabled,
        hostAutolearnPenaltyTtlHours = settings.hostAutolearnPenaltyTtlHours,
        hostAutolearnMaxHosts = settings.hostAutolearnMaxHosts,
        hostAutolearnStorePath = hostAutolearnStorePath,
    )

    override fun toNativeConfigJson(): String =
        NativeProxyJson.encodeToString<NativeProxyConfig>(
            NativeProxyConfig.Ui(
                ip = ip,
                port = port,
                maxConnections = maxConnections,
                bufferSize = bufferSize,
                defaultTtl = defaultTtl,
                customTtl = customTtl,
                noDomain = noDomain,
                desyncHttp = desyncHttp,
                desyncHttps = desyncHttps,
                desyncUdp = desyncUdp,
                desyncMethod = desyncMethod.wireName,
                splitMarker = splitMarker,
                groupActivationFilter = groupActivationFilter.toNative(),
                tcpChainSteps = tcpChainSteps.map {
                    val step = normalizeTcpChainStepModel(it)
                    NativeProxyConfig.NativeTcpChainStep(
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
                fakeTtl = fakeTtl,
                fakeSni = fakeSni,
                httpFakeProfile = httpFakeProfile,
                fakeTlsUseOriginal = fakeTlsUseOriginal,
                fakeTlsRandomize = fakeTlsRandomize,
                fakeTlsDupSessionId = fakeTlsDupSessionId,
                fakeTlsPadEncap = fakeTlsPadEncap,
                fakeTlsSize = fakeTlsSize,
                fakeTlsSniMode = fakeTlsSniMode,
                tlsFakeProfile = tlsFakeProfile,
                oobChar = oobChar.toInt() and 0xFF,
                hostMixedCase = hostMixedCase,
                domainMixedCase = domainMixedCase,
                hostRemoveSpaces = hostRemoveSpaces,
                tlsRecordSplit = tlsRecordSplit,
                tlsRecordSplitMarker = tlsRecordSplitMarker,
                hostsMode = hostsMode.wireName,
                hosts = hosts,
                tcpFastOpen = tcpFastOpen,
                udpFakeCount = udpFakeCount,
                udpChainSteps = udpChainSteps.map {
                    NativeProxyConfig.NativeUdpChainStep(
                        kind = it.kind.wireName,
                        count = it.count,
                        activationFilter = it.activationFilter.toNative(),
                    )
                },
                udpFakeProfile = udpFakeProfile,
                dropSack = dropSack,
                fakeOffsetMarker = fakeOffsetMarker,
                quicInitialMode = quicInitialMode,
                quicSupportV1 = quicSupportV1,
                quicSupportV2 = quicSupportV2,
                quicFakeProfile = quicFakeProfile,
                quicFakeHost = quicFakeHost,
                hostAutolearnEnabled = hostAutolearnEnabled,
                hostAutolearnPenaltyTtlSecs = hostAutolearnPenaltyTtlHours * 60 * 60,
                hostAutolearnMaxHosts = hostAutolearnMaxHosts,
                hostAutolearnStorePath = hostAutolearnStorePath,
            ),
        )

    enum class DesyncMethod {
        None,
        Split,
        Disorder,
        Fake,
        OOB,
        DISOOB,
        ;

        companion object {
            fun fromName(name: String): DesyncMethod =
                when (name) {
                    "none" -> None
                    "split" -> Split
                    "disorder" -> Disorder
                    "fake" -> Fake
                    "oob" -> OOB
                    "disoob" -> DISOOB
                    else -> throw IllegalArgumentException("Unknown desync method: $name")
                }
        }

        val wireName: String
            get() =
                when (this) {
                    None -> "none"
                    Split -> "split"
                    Disorder -> "disorder"
                    Fake -> "fake"
                    OOB -> "oob"
                    DISOOB -> "disoob"
                }
    }

    enum class HostsMode {
        Disable,
        Blacklist,
        Whitelist,
        ;

        companion object {
            fun fromName(name: String): HostsMode =
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

@Serializable
private sealed interface NativeProxyConfig {
    @Serializable
    data class NativeTcpChainStep(
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
    data class NativeUdpChainStep(
        val kind: String,
        val count: Int,
        val activationFilter: NativeActivationFilter? = null,
    )

    @Serializable
    @SerialName("command_line")
    data class CommandLine(
        val args: List<String>,
    ) : NativeProxyConfig

    @Serializable
    @SerialName("ui")
    data class Ui(
        val ip: String,
        val port: Int,
        val maxConnections: Int,
        val bufferSize: Int,
        val defaultTtl: Int,
        val customTtl: Boolean,
        val noDomain: Boolean,
        val desyncHttp: Boolean,
        val desyncHttps: Boolean,
        val desyncUdp: Boolean,
        val desyncMethod: String,
        val splitMarker: String,
        val groupActivationFilter: NativeActivationFilter? = null,
        val tcpChainSteps: List<NativeTcpChainStep>,
        val fakeTtl: Int,
        val fakeSni: String,
        val httpFakeProfile: String = FakePayloadProfileCompatDefault,
        val fakeTlsUseOriginal: Boolean,
        val fakeTlsRandomize: Boolean,
        val fakeTlsDupSessionId: Boolean,
        val fakeTlsPadEncap: Boolean,
        val fakeTlsSize: Int,
        val fakeTlsSniMode: String,
        val tlsFakeProfile: String = FakePayloadProfileCompatDefault,
        val oobChar: Int,
        val hostMixedCase: Boolean,
        val domainMixedCase: Boolean,
        val hostRemoveSpaces: Boolean,
        val tlsRecordSplit: Boolean,
        val tlsRecordSplitMarker: String,
        val hostsMode: String,
        val hosts: String?,
        val tcpFastOpen: Boolean,
        val udpFakeCount: Int,
        val udpChainSteps: List<NativeUdpChainStep>,
        val udpFakeProfile: String = FakePayloadProfileCompatDefault,
        val dropSack: Boolean,
        val fakeOffsetMarker: String,
        val quicInitialMode: String,
        val quicSupportV1: Boolean,
        val quicSupportV2: Boolean,
        val quicFakeProfile: String,
        val quicFakeHost: String,
        val hostAutolearnEnabled: Boolean,
        val hostAutolearnPenaltyTtlSecs: Int,
        val hostAutolearnMaxHosts: Int,
        val hostAutolearnStorePath: String?,
    ) : NativeProxyConfig
}

private fun buildLegacyTcpChain(
    desyncMethod: RipDpiProxyUIPreferences.DesyncMethod,
    splitMarker: String,
    tlsRecordSplit: Boolean,
    tlsRecordSplitMarker: String?,
): List<TcpChainStepModel> =
    buildList {
        if (tlsRecordSplit) {
            add(TcpChainStepModel(TcpChainStepKind.TlsRec, normalizeOffsetExpression(tlsRecordSplitMarker.orEmpty(), DefaultTlsRecordMarker)))
        }
        when (desyncMethod) {
            RipDpiProxyUIPreferences.DesyncMethod.None -> Unit
            RipDpiProxyUIPreferences.DesyncMethod.Split -> add(TcpChainStepModel(TcpChainStepKind.Split, splitMarker))
            RipDpiProxyUIPreferences.DesyncMethod.Disorder -> add(TcpChainStepModel(TcpChainStepKind.Disorder, splitMarker))
            RipDpiProxyUIPreferences.DesyncMethod.Fake -> add(TcpChainStepModel(TcpChainStepKind.Fake, splitMarker))
            RipDpiProxyUIPreferences.DesyncMethod.OOB -> add(TcpChainStepModel(TcpChainStepKind.Oob, splitMarker))
            RipDpiProxyUIPreferences.DesyncMethod.DISOOB -> add(TcpChainStepModel(TcpChainStepKind.Disoob, splitMarker))
        }
    }

private fun buildLegacyUdpChain(udpFakeCount: Int): List<UdpChainStepModel> =
    if (udpFakeCount > 0) {
        listOf(UdpChainStepModel(count = udpFakeCount))
    } else {
        emptyList()
    }

private fun normalizeMarkerForStep(
    kind: TcpChainStepKind,
    marker: String,
): String {
    val defaultValue = if (kind.isTlsPrelude) DefaultTlsRecordMarker else DefaultSplitMarker
    return normalizeOffsetExpression(marker, defaultValue)
}

private fun normalizeTcpChainStep(step: TcpChainStepModel): TcpChainStepModel =
    normalizeTcpChainStepModel(step.copy(marker = normalizeMarkerForStep(step.kind, step.marker)))
