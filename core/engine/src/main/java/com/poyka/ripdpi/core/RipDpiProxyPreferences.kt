package com.poyka.ripdpi.core

import com.poyka.ripdpi.data.DefaultFakeOffsetMarker
import com.poyka.ripdpi.data.DefaultFakeSni
import com.poyka.ripdpi.data.DefaultHostAutolearnMaxHosts
import com.poyka.ripdpi.data.DefaultHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.DefaultSplitMarker
import com.poyka.ripdpi.data.DefaultTlsRecordMarker
import com.poyka.ripdpi.data.effectiveFakeTlsSniMode
import com.poyka.ripdpi.data.QuicInitialModeRouteAndCache
import com.poyka.ripdpi.data.TcpChainStepKind
import com.poyka.ripdpi.data.TcpChainStepModel
import com.poyka.ripdpi.data.UdpChainStepModel
import com.poyka.ripdpi.data.effectiveFakeOffsetMarker
import com.poyka.ripdpi.data.effectiveQuicInitialMode
import com.poyka.ripdpi.data.effectiveQuicSupportV1
import com.poyka.ripdpi.data.effectiveQuicSupportV2
import com.poyka.ripdpi.data.effectiveSplitMarker
import com.poyka.ripdpi.data.effectiveTcpChainSteps
import com.poyka.ripdpi.data.effectiveTlsRecordMarker
import com.poyka.ripdpi.data.effectiveUdpChainSteps
import com.poyka.ripdpi.data.formatChainSummary
import com.poyka.ripdpi.data.normalizeHostAutolearnMaxHosts
import com.poyka.ripdpi.data.normalizeHostAutolearnPenaltyTtlHours
import com.poyka.ripdpi.data.normalizeFakeTlsSniMode
import com.poyka.ripdpi.data.normalizeQuicInitialMode
import com.poyka.ripdpi.data.normalizeOffsetExpression
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
    tcpChainSteps: List<TcpChainStepModel>? = null,
    fakeTtl: Int? = null,
    fakeSni: String? = null,
    fakeTlsUseOriginal: Boolean? = null,
    fakeTlsRandomize: Boolean? = null,
    fakeTlsDupSessionId: Boolean? = null,
    fakeTlsPadEncap: Boolean? = null,
    fakeTlsSize: Int? = null,
    fakeTlsSniMode: String? = null,
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
    dropSack: Boolean? = null,
    fakeOffsetMarker: String? = null,
    udpChainSteps: List<UdpChainStepModel>? = null,
    quicInitialMode: String? = null,
    quicSupportV1: Boolean? = null,
    quicSupportV2: Boolean? = null,
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
    val tcpChainSteps: List<TcpChainStepModel> =
        tcpChainSteps?.map { it.copy(marker = normalizeMarkerForStep(it.kind, it.marker)) }
            ?: buildLegacyTcpChain(
                desyncMethod = this.desyncMethod,
                splitMarker = this.splitMarker,
                tlsRecordSplit = tlsRecordSplit ?: false,
                tlsRecordSplitMarker = tlsRecordSplitMarker,
            )
    val fakeTtl: Int = fakeTtl ?: 8
    val fakeSni: String = fakeSni ?: DefaultFakeSni
    val fakeTlsUseOriginal: Boolean = fakeTlsUseOriginal ?: false
    val fakeTlsRandomize: Boolean = fakeTlsRandomize ?: false
    val fakeTlsDupSessionId: Boolean = fakeTlsDupSessionId ?: false
    val fakeTlsPadEncap: Boolean = fakeTlsPadEncap ?: false
    val fakeTlsSize: Int = fakeTlsSize ?: 0
    val fakeTlsSniMode: String = normalizeFakeTlsSniMode(fakeTlsSniMode.orEmpty())
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
    val dropSack: Boolean = dropSack ?: false
    val fakeOffsetMarker: String = normalizeOffsetExpression(fakeOffsetMarker.orEmpty(), DefaultFakeOffsetMarker)
    val quicInitialMode: String = normalizeQuicInitialMode(quicInitialMode.orEmpty().ifBlank { QuicInitialModeRouteAndCache })
    val quicSupportV1: Boolean = quicSupportV1 ?: true
    val quicSupportV2: Boolean = quicSupportV2 ?: true
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
        tcpChainSteps = settings.effectiveTcpChainSteps(),
        fakeTtl = settings.fakeTtl.takeIf { it > 0 },
        fakeSni = settings.fakeSni.ifEmpty { null },
        fakeTlsUseOriginal = settings.fakeTlsUseOriginal,
        fakeTlsRandomize = settings.fakeTlsRandomize,
        fakeTlsDupSessionId = settings.fakeTlsDupSessionId,
        fakeTlsPadEncap = settings.fakeTlsPadEncap,
        fakeTlsSize = settings.fakeTlsSize,
        fakeTlsSniMode = settings.effectiveFakeTlsSniMode(),
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
        dropSack = settings.dropSack,
        fakeOffsetMarker = settings.effectiveFakeOffsetMarker(),
        udpChainSteps = settings.effectiveUdpChainSteps(),
        quicInitialMode = settings.effectiveQuicInitialMode(),
        quicSupportV1 = settings.effectiveQuicSupportV1(),
        quicSupportV2 = settings.effectiveQuicSupportV2(),
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
                tcpChainSteps = tcpChainSteps.map {
                    NativeProxyConfig.NativeTcpChainStep(kind = it.kind.wireName, marker = it.marker)
                },
                fakeTtl = fakeTtl,
                fakeSni = fakeSni,
                fakeTlsUseOriginal = fakeTlsUseOriginal,
                fakeTlsRandomize = fakeTlsRandomize,
                fakeTlsDupSessionId = fakeTlsDupSessionId,
                fakeTlsPadEncap = fakeTlsPadEncap,
                fakeTlsSize = fakeTlsSize,
                fakeTlsSniMode = fakeTlsSniMode,
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
                    NativeProxyConfig.NativeUdpChainStep(kind = it.kind.wireName, count = it.count)
                },
                dropSack = dropSack,
                fakeOffsetMarker = fakeOffsetMarker,
                quicInitialMode = quicInitialMode,
                quicSupportV1 = quicSupportV1,
                quicSupportV2 = quicSupportV2,
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
    )

    @Serializable
    data class NativeUdpChainStep(
        val kind: String,
        val count: Int,
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
        val tcpChainSteps: List<NativeTcpChainStep>,
        val fakeTtl: Int,
        val fakeSni: String,
        val fakeTlsUseOriginal: Boolean,
        val fakeTlsRandomize: Boolean,
        val fakeTlsDupSessionId: Boolean,
        val fakeTlsPadEncap: Boolean,
        val fakeTlsSize: Int,
        val fakeTlsSniMode: String,
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
        val dropSack: Boolean,
        val fakeOffsetMarker: String,
        val quicInitialMode: String,
        val quicSupportV1: Boolean,
        val quicSupportV2: Boolean,
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
    val defaultValue = if (kind == TcpChainStepKind.TlsRec) DefaultTlsRecordMarker else DefaultSplitMarker
    return normalizeOffsetExpression(marker, defaultValue)
}
