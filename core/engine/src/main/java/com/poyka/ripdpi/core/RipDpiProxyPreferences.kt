package com.poyka.ripdpi.core

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
    splitPosition: Int? = null,
    splitAtHost: Boolean? = null,
    fakeTtl: Int? = null,
    fakeSni: String? = null,
    oobChar: String? = null,
    hostMixedCase: Boolean? = null,
    domainMixedCase: Boolean? = null,
    hostRemoveSpaces: Boolean? = null,
    tlsRecordSplit: Boolean? = null,
    tlsRecordSplitPosition: Int? = null,
    tlsRecordSplitAtSni: Boolean? = null,
    hostsMode: HostsMode? = null,
    hosts: String? = null,
    tcpFastOpen: Boolean? = null,
    udpFakeCount: Int? = null,
    dropSack: Boolean? = null,
    ripdpiFakeOffset: Int? = null,
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
    val splitPosition: Int = splitPosition ?: 1
    val splitAtHost: Boolean = splitAtHost ?: false
    val fakeTtl: Int = fakeTtl ?: 8
    val fakeSni: String = fakeSni ?: "www.iana.org"
    val oobChar: Byte = (oobChar ?: "a")[0].code.toByte()
    val hostMixedCase: Boolean = hostMixedCase ?: false
    val domainMixedCase: Boolean = domainMixedCase ?: false
    val hostRemoveSpaces: Boolean = hostRemoveSpaces ?: false
    val tlsRecordSplit: Boolean = tlsRecordSplit ?: false
    val tlsRecordSplitPosition: Int = tlsRecordSplitPosition ?: 0
    val tlsRecordSplitAtSni: Boolean = tlsRecordSplitAtSni ?: false
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
    val dropSack: Boolean = dropSack ?: false
    val fakeOffset: Int = ripdpiFakeOffset ?: 0

    constructor(settings: AppSettings) : this(
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
        splitPosition = settings.splitPosition,
        splitAtHost = settings.splitAtHost,
        fakeTtl = settings.fakeTtl.takeIf { it > 0 },
        fakeSni = settings.fakeSni.ifEmpty { null },
        oobChar = settings.oobData.ifEmpty { null },
        hostMixedCase = settings.hostMixedCase,
        domainMixedCase = settings.domainMixedCase,
        hostRemoveSpaces = settings.hostRemoveSpaces,
        tlsRecordSplit = settings.tlsrecEnabled,
        tlsRecordSplitPosition = settings.tlsrecPosition,
        tlsRecordSplitAtSni = settings.tlsrecAtSni,
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
        ripdpiFakeOffset = settings.fakeOffset,
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
                splitPosition = splitPosition,
                splitAtHost = splitAtHost,
                fakeTtl = fakeTtl,
                fakeSni = fakeSni,
                oobChar = oobChar.toInt() and 0xFF,
                hostMixedCase = hostMixedCase,
                domainMixedCase = domainMixedCase,
                hostRemoveSpaces = hostRemoveSpaces,
                tlsRecordSplit = tlsRecordSplit,
                tlsRecordSplitPosition = tlsRecordSplitPosition,
                tlsRecordSplitAtSni = tlsRecordSplitAtSni,
                hostsMode = hostsMode.wireName,
                hosts = hosts,
                tcpFastOpen = tcpFastOpen,
                udpFakeCount = udpFakeCount,
                dropSack = dropSack,
                fakeOffset = fakeOffset,
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
        val splitPosition: Int,
        val splitAtHost: Boolean,
        val fakeTtl: Int,
        val fakeSni: String,
        val oobChar: Int,
        val hostMixedCase: Boolean,
        val domainMixedCase: Boolean,
        val hostRemoveSpaces: Boolean,
        val tlsRecordSplit: Boolean,
        val tlsRecordSplitPosition: Int,
        val tlsRecordSplitAtSni: Boolean,
        val hostsMode: String,
        val hosts: String?,
        val tcpFastOpen: Boolean,
        val udpFakeCount: Int,
        val dropSack: Boolean,
        val fakeOffset: Int,
    ) : NativeProxyConfig
}
