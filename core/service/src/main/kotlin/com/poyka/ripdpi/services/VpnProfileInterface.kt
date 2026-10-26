package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.ServiceStartupRejectedException
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
    if (routed.isEmpty()) {
        rejectProfile("AmneziaWG AllowedIPs route no address family that the profile configures")
    }
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
                        VpnTunnelRouteEntry(address, if (address.isIpv6Literal()) Ipv6PrefixBits else Ipv4PrefixBits)
                    }
            ).distinct(),
    )
}

/** One DNS policy for host routes and Builder DNS; neither may enable an unconfigured family. */
internal fun VpnProfileInterface.effectiveDnsServers(fallbackDns: String): List<String> {
    val effective = dnsServers.ifEmpty { listOf(fallbackDns) }.filter(String::isNotBlank)
    // A DNS server also allows its family, so drop servers of a family that AllowedIPs leaves unrouted.
    // An unconfigured family has no routable AllowedIPs, so its servers are dropped here as well.
    val routed = routedFamilies()
    val reachable = effective.filter { it.isIpv6Literal() in routed }
    if (effective.isNotEmpty() && reachable.isEmpty()) {
        rejectProfile(
            if (dnsServers.isEmpty()) {
                "AmneziaWG profile has no DNS server, and AllowedIPs do not route the built-in DNS family; " +
                    "add a DNS server of a routed address family"
            } else {
                "AmneziaWG DNS servers use only address families that AllowedIPs does not route; " +
                    "add a DNS server of a routed address family"
            },
        )
    }
    return reachable
}

private fun VpnProfileInterface.routableAllowedIps(): List<String> = allowedIps.filter { ipv6Enabled || ':' !in it }

/** Address families (true = IPv6) that have at least one AllowedIPs route into the tunnel. */
private fun VpnProfileInterface.routedFamilies(): Set<Boolean> = routableAllowedIps().map { ':' in it }.toSet()

/** A profile the user must change; the typed reason reaches ServiceStatus.Failed with this text. */
private fun rejectProfile(message: String): Nothing =
    throw ServiceStartupRejectedException(FailureReason.RelayConfigRejected(message))

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
