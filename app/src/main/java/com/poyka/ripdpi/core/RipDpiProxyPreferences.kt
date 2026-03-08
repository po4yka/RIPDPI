package com.poyka.ripdpi.core

import android.content.SharedPreferences
import com.poyka.ripdpi.utility.getStringNotNull
import com.poyka.ripdpi.utility.shellSplit

sealed interface RipDpiProxyPreferences {
    companion object {
        fun fromSharedPreferences(preferences: SharedPreferences): RipDpiProxyPreferences =
            when (preferences.getBoolean("ripdpi_enable_cmd_settings", false)) {
                true -> RipDpiProxyCmdPreferences(preferences)
                false -> RipDpiProxyUIPreferences(preferences)
            }
    }
}

class RipDpiProxyCmdPreferences(val args: Array<String>) : RipDpiProxyPreferences {
    constructor(cmd: String) : this(cmdToArgs(cmd))

    constructor(preferences: SharedPreferences) : this(
        preferences.getStringNotNull(
            "ripdpi_cmd_args",
            ""
        )
    )

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
        if (hosts?.isBlank() != false) HostsMode.Disable
        else hostsMode ?: HostsMode.Disable
    val hosts: String? =
        if (this.hostsMode == HostsMode.Disable) null
        else hosts?.trim()
    val tcpFastOpen: Boolean = tcpFastOpen ?: false
    val udpFakeCount: Int = udpFakeCount ?: 0
    val dropSack: Boolean = dropSack ?: false
    val fakeOffset: Int = ripdpiFakeOffset ?: 0

    constructor(preferences: SharedPreferences) : this(
        ip = preferences.getString("ripdpi_proxy_ip", null),
        port = preferences.getString("ripdpi_proxy_port", null)?.toIntOrNull(),
        maxConnections = preferences.getString("ripdpi_max_connections", null)?.toIntOrNull(),
        bufferSize = preferences.getString("ripdpi_buffer_size", null)?.toIntOrNull(),
        defaultTtl = preferences.getString("ripdpi_default_ttl", null)?.toIntOrNull(),
        noDomain = preferences.getBoolean("ripdpi_no_domain", false),
        desyncHttp = preferences.getBoolean("ripdpi_desync_http", true),
        desyncHttps = preferences.getBoolean("ripdpi_desync_https", true),
        desyncUdp = preferences.getBoolean("ripdpi_desync_udp", false),
        desyncMethod = preferences.getString("ripdpi_desync_method", null)
            ?.let { DesyncMethod.fromName(it) },
        splitPosition = preferences.getString("ripdpi_split_position", null)?.toIntOrNull(),
        splitAtHost = preferences.getBoolean("ripdpi_split_at_host", false),
        fakeTtl = preferences.getString("ripdpi_fake_ttl", null)?.toIntOrNull(),
        fakeSni = preferences.getString("ripdpi_fake_sni", null),
        oobChar = preferences.getString("ripdpi_oob_data", null),
        hostMixedCase = preferences.getBoolean("ripdpi_host_mixed_case", false),
        domainMixedCase = preferences.getBoolean("ripdpi_domain_mixed_case", false),
        hostRemoveSpaces = preferences.getBoolean("ripdpi_host_remove_spaces", false),
        tlsRecordSplit = preferences.getBoolean("ripdpi_tlsrec_enabled", false),
        tlsRecordSplitPosition = preferences.getString("ripdpi_tlsrec_position", null)
            ?.toIntOrNull(),
        tlsRecordSplitAtSni = preferences.getBoolean("ripdpi_tlsrec_at_sni", false),
        hostsMode = preferences.getString("ripdpi_hosts_mode", null)
            ?.let { HostsMode.fromName(it) },
        hosts = preferences.getString("ripdpi_hosts_mode", null)?.let {
            when (HostsMode.fromName(it)) {
                HostsMode.Blacklist -> preferences.getString("ripdpi_hosts_blacklist", null)
                HostsMode.Whitelist -> preferences.getString("ripdpi_hosts_whitelist", null)
                else -> null
            }
        },
        tcpFastOpen = preferences.getBoolean("ripdpi_tcp_fast_open", false),
        udpFakeCount = preferences.getString("ripdpi_udp_fake_count", null)?.toIntOrNull(),
        dropSack = preferences.getBoolean("ripdpi_drop_sack", false),
        ripdpiFakeOffset = preferences.getString("ripdpi_fake_offset", null)?.toIntOrNull(),
    )

    enum class DesyncMethod {
        None,
        Split,
        Disorder,
        Fake,
        OOB,
        DISOOB;

        companion object {
            fun fromName(name: String): DesyncMethod {
                return when (name) {
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
    }

    enum class HostsMode {
        Disable,
        Blacklist,
        Whitelist;

        companion object {
            fun fromName(name: String): HostsMode {
                return when (name) {
                    "disable" -> Disable
                    "blacklist" -> Blacklist
                    "whitelist" -> Whitelist
                    else -> throw IllegalArgumentException("Unknown hosts mode: $name")
                }
            }
        }
    }
}
