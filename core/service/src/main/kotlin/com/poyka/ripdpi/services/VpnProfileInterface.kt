package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.awg.AwgActivationRequest
import java.net.InetAddress
import java.net.URI

/** Service-owned interface policy from an explicitly selected standalone profile. */
data class VpnProfileInterface(
    val dnsServers: List<String>,
    val allowedIps: List<String>,
    val ipv6Enabled: Boolean,
    val mtu: Int,
)

internal fun AwgActivationRequest.vpnProfileInterface(): VpnProfileInterface =
    VpnProfileInterface(dnsServers.toList(), allowedIps.toList(), interfaceAddressV6.isNotBlank(), mtu)

internal fun VpnProfileInterface.routePlan(fallbackDns: String = ""): VpnTunnelRoutePlan =
    RipDpiVpnService.vpnTunnelRoutePlan(ipv6Enabled).copy(
        routes =
            (
                allowedIps.map { cidr ->
                    val (address, prefixText) = cidr.split('/')
                    val prefix = prefixText.toInt()
                    // Inputs are validated numeric literals; never resolve a hostname here.
                    require(address.all { it in "0123456789abcdefABCDEF:." })
                    require(
                        ':' in address ||
                            address.split('.').let { parts ->
                                parts.size == Ipv4Octets && parts.all { it.toIntOrNull() in 0..UnsignedByteMask }
                            },
                    )
                    val bytes = numericAddressBytes(address)
                    require(prefix in 0..bytes.size * ByteBits)
                    bytes.indices.forEach { index ->
                        val kept = (prefix - index * ByteBits).coerceIn(0, ByteBits)
                        bytes[index] = (bytes[index].toInt() and (UnsignedByteMask shl (ByteBits - kept))).toByte()
                    }
                    VpnTunnelRouteEntry(checkNotNull(InetAddress.getByAddress(bytes).hostAddress), prefix)
                } +
                    (dnsServers.ifEmpty { listOf(fallbackDns) }).filter(String::isNotBlank).map { address ->
                        VpnTunnelRouteEntry(address, if (':' in address) Ipv6PrefixBits else Ipv4PrefixBits)
                    }
            ).distinct(),
    )

/** URI validates IPv6 grammar; expanding its numeric groups never invokes a resolver. */
private fun numericAddressBytes(address: String): ByteArray {
    if (':' !in address) return address.split('.').map { it.toInt().toByte() }.toByteArray()
    require('.' !in address && URI("http://[$address]/").host != null)
    val halves = address.split("::", limit = 2)
    val first = halves.first().split(':').filter(String::isNotEmpty)
    val last =
        halves
            .getOrNull(1)
            ?.split(':')
            ?.filter(String::isNotEmpty)
            .orEmpty()
    val groups = if (halves.size == 2) first + List(Ipv6Groups - first.size - last.size) { "0" } + last else first
    require(groups.size == Ipv6Groups)
    return groups
        .flatMap { group ->
            val value = group.toInt(HexRadix)
            listOf((value ushr ByteBits).toByte(), value.toByte())
        }.toByteArray()
}

private const val Ipv4Octets = 4
private const val ByteBits = 8
private const val UnsignedByteMask = 255
private const val Ipv4PrefixBits = 32
private const val Ipv6PrefixBits = 128
private const val Ipv6Groups = 8
private const val HexRadix = 16
