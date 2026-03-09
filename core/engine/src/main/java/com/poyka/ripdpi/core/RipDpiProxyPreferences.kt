package com.poyka.ripdpi.core

import android.content.Context
import com.poyka.ripdpi.data.settingsStore
import com.poyka.ripdpi.proto.AppSettings
import com.poyka.ripdpi.utility.shellSplit
import kotlinx.coroutines.flow.first

sealed interface RipDpiProxyPreferences {
    companion object {
        suspend fun fromSettingsStore(context: Context): RipDpiProxyPreferences {
            val settings = context.settingsStore.data.first()
            return if (settings.enableCmdSettings) {
                RipDpiProxyCmdPreferences(settings.cmdArgs)
            } else {
                RipDpiProxyUIPreferences(settings)
            }
        }
    }
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
    }
}
