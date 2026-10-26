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

internal fun VpnProfileInterface.routePlan(fallbackDns: String = ""): VpnTunnelRoutePlan {
    val routed = routedFamilies()
    require(routed.isNotEmpty()) { "AmneziaWG AllowedIPs must route a configured interface family" }
    val basePlan = RipDpiVpnService.vpnTunnelRoutePlan(ipv6Enabled)
    return basePlan.copy(
        // Android allows a family that has a TUN address; without a route for it, that traffic
        // would leave on the underlying network. Leave the address out so Android blocks the family.
        addresses = basePlan.addresses.filter { it.address.isIpv6Literal() in routed },
        routes =
            (
                // Preserve the source list, but do not enable an unconfigured Android address family.
                routableAllowedIps().map { cidr ->
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
                    effectiveDnsServers(fallbackDns).map { address ->
                        when {
                            // MapDNS answers from its synthetic pool; those addresses must enter the TUN.
                            address == VpnInterceptorDnsAddress -> {
                                VpnTunnelRouteEntry(MapDnsPoolNetwork, MapDnsPoolPrefixBits)
                            }

                            address.isIpv6Literal() -> {
                                VpnTunnelRouteEntry(address, Ipv6PrefixBits)
                            }

                            else -> {
                                VpnTunnelRouteEntry(address, Ipv4PrefixBits)
                            }
                        }
                    }
            ).distinct(),
    )
}

/** One DNS policy for host routes and Builder DNS; neither may enable an unconfigured family. */
internal fun VpnProfileInterface.effectiveDnsServers(fallbackDns: String): List<String> {
    val effective = dnsServers.ifEmpty { listOf(fallbackDns) }.filter(String::isNotBlank)
    require(ipv6Enabled || effective.none { ':' in it }) {
        "AmneziaWG DNS servers require a configured interface family"
    }
    // A DNS server also allows its family, so drop servers of a family that AllowedIPs leaves unrouted.
    val routed = routedFamilies()
    val reachable = effective.filter { it.isIpv6Literal() in routed }
    require(effective.isEmpty() || reachable.isNotEmpty()) {
        "AmneziaWG DNS servers require an address family that AllowedIPs routes"
    }
    return reachable
}

private fun VpnProfileInterface.routableAllowedIps(): List<String> = allowedIps.filter { ipv6Enabled || ':' !in it }

/** Address families (true = IPv6) that have at least one AllowedIPs route into the tunnel. */
private fun VpnProfileInterface.routedFamilies(): Set<Boolean> = routableAllowedIps().map { ':' in it }.toSet()

private fun String.isIpv6Literal(): Boolean = ':' in this

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
private const val MapDnsPoolNetwork = "198.18.0.0"
private const val MapDnsPoolPrefixBits = 15
