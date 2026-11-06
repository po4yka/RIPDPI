@file:Suppress("ReturnCount")

package com.poyka.ripdpi.core.detection.privacy

import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.data.NumericIp
import java.net.Inet4Address
import java.net.Inet6Address

object DetectionPrivacyMask {
    private val ipv4Regex = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    private val ipv6Regex = Regex("""(?i)(?<![0-9a-z_.:])(?:[0-9a-f]*:){2,}[0-9a-f]*(?![0-9a-z_.:])""")

    fun maskIp(
        ip: String,
        enabled: Boolean,
    ): String {
        if (!enabled) return ip
        val parsed = NumericIp.parseAddress(ip) ?: return ip
        val localAddress = if (parsed.isIPv6 && parsed.toIPv6().isIPv4Mapped) parsed.toIPv4() else parsed
        val peer = localAddress.toInetAddress()
        if (peer.isLoopbackAddress || peer.isLinkLocalAddress || peer.isSiteLocalAddress) return ip

        return when (val address = parsed.toInetAddress()) {
            is Inet4Address -> maskIpv4(ip)
            is Inet6Address -> maskIpv6(address)
            else -> ip
        }
    }

    fun maskIpsInText(
        text: String,
        enabled: Boolean,
    ): String {
        if (!enabled) return text
        val ipv4Masked = ipv4Regex.replace(text) { match -> maskIp(match.value, enabled = true) }
        return ipv6Regex.replace(ipv4Masked) { match -> maskIp(match.value, enabled = true) }
    }

    /**
     * Hides another app's proxy server names (outbound address, SNI, tag) in privacy mode. IP literals
     * are left to [maskIpsInText]. Names are matched as plain text, so they are hidden in free-text
     * findings too.
     */
    fun maskHostNamesInText(
        text: String,
        hostNames: Collection<String?>,
        enabled: Boolean,
    ): String {
        if (!enabled) return text
        return hostNames
            .filterNotNull()
            .map(String::trim)
            .filter { name -> name.contains('.') && !ipv4Regex.matches(name) && !name.contains(':') }
            .distinct()
            .sortedByDescending(String::length)
            .fold(text) { masked, name -> masked.replace(name, HIDDEN_VALUE, ignoreCase = true) }
    }

    const val HIDDEN_VALUE = "***"

    private fun maskIpv4(ip: String): String {
        val parts = ip.split(".")
        return "${parts[0]}.${parts[1]}.*.*"
    }

    private fun maskIpv6(address: Inet6Address): String {
        val groups =
            address.address
                .asList()
                .chunked(IPV6_BYTES_PER_GROUP)
                .map { bytes ->
                    val high = bytes[0].toInt() and BYTE_MASK
                    val low = bytes[1].toInt() and BYTE_MASK
                    ((high shl Byte.SIZE_BITS) or low).toString(IPV6_RADIX)
                }
        return groups
            .take(IPV6_VISIBLE_GROUPS)
            .joinToString(":")
            .plus(":****:****:****:****")
    }

    private const val BYTE_MASK = 0xFF
    private const val IPV6_BYTES_PER_GROUP = 2
    private const val IPV6_VISIBLE_GROUPS = 4
    private const val IPV6_RADIX = 16
}

/** Server names of the outbounds a local Xray API reported; exports hide them in privacy mode. */
internal fun BypassResult.xrayHostNames(): List<String?> =
    xrayApiScanResult
        ?.outbounds
        .orEmpty()
        .flatMap { outbound -> listOf(outbound.address, outbound.sni, outbound.tag) }
