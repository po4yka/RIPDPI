package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.FailureReason
import com.poyka.ripdpi.data.NumericIp
import com.poyka.ripdpi.data.ServiceStartupRejectedException
import com.poyka.ripdpi.data.awg.AwgActivationRequest

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
                    val network = requireNotNull(NumericIp.parseCidr(cidr))
                    VpnTunnelRouteEntry(network.networkAddress.toNormalizedString(), network.prefixLength)
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

private fun String.isIpv6Literal(): Boolean = NumericIp.parseAddress(this)?.isIPv6 == true

private const val Ipv4PrefixBits = 32
private const val Ipv6PrefixBits = 128
