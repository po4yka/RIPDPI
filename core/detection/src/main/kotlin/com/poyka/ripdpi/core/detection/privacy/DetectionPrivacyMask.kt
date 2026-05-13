@file:Suppress("ReturnCount")

package com.poyka.ripdpi.core.detection.privacy

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

object DetectionPrivacyMask {
    private val ipv4Regex = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    private val ipv6Regex = Regex("""(?i)\b(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{0,4}\b""")

    fun maskIp(
        ip: String,
        enabled: Boolean,
    ): String {
        if (!enabled) return ip
        val address = parseAddress(ip) ?: return ip
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return ip

        return when (address) {
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

    private fun maskIpv4(ip: String): String {
        val parts = ip.split(".")
        if (parts.size != IPV4_PART_COUNT || parts.any { it.toIntOrNull() !in IPV4_OCTET_RANGE }) return ip
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

    private fun parseAddress(ip: String): InetAddress? =
        runCatching {
            InetAddress.getByName(ip.lowercase(Locale.US))
        }.getOrNull()

    private const val BYTE_MASK = 0xFF
    private const val IPV4_PART_COUNT = 4
    private const val IPV6_BYTES_PER_GROUP = 2
    private const val IPV6_VISIBLE_GROUPS = 4
    private const val IPV6_RADIX = 16
    private val IPV4_OCTET_RANGE = 0..255
}
