package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.Ipv6Policy

/**
 * Translates an [Ipv6Policy] into a concrete set of VPN-builder decisions.
 *
 * Callers use [decide] to obtain an [Ipv6BuilderDecision] and then apply each
 * flag to the Android [android.net.VpnService.Builder] independently.
 * This keeps policy logic out of the builder invocation site.
 */
internal object Ipv6PolicyEnforcer {
    /**
     * Maps [policy] to the set of VPN builder artefacts that must be configured.
     *
     * The secure default ([Ipv6Policy.IPV4_ONLY]) produces an all-false decision:
     * no IPv6 address, no default IPv6 route, no AAAA DNS forwarding, no AF_INET6
     * family allowance.
     */
    fun decide(policy: Ipv6Policy): Ipv6BuilderDecision =
        when (policy) {
            Ipv6Policy.ALLOW, Ipv6Policy.PREFER -> {
                Ipv6BuilderDecision(
                    addIpv6TunnelAddress = true,
                    addDefaultIpv6Route = true,
                    forwardAaaaDnsThroughTunnel = true,
                    allowInet6Family = true,
                )
            }

            Ipv6Policy.IPV4_ONLY, Ipv6Policy.BLOCK, Ipv6Policy.NATIVE, Ipv6Policy.TRANSLATED -> {
                Ipv6BuilderDecision(
                    addIpv6TunnelAddress = false,
                    addDefaultIpv6Route = false,
                    forwardAaaaDnsThroughTunnel = false,
                    allowInet6Family = false,
                )
            }
        }
}

/**
 * Concrete VPN-builder decisions derived from an [Ipv6Policy].
 *
 * @param addIpv6TunnelAddress       Whether to call addAddress with an IPv6 ULA address.
 * @param addDefaultIpv6Route        Whether to call addRoute("::", 0) — the ::/0 default.
 * @param forwardAaaaDnsThroughTunnel Whether AAAA queries must be routed through the tunnel.
 * @param allowInet6Family           Whether to call allowFamily(OsConstants.AF_INET6).
 */
internal data class Ipv6BuilderDecision(
    val addIpv6TunnelAddress: Boolean,
    val addDefaultIpv6Route: Boolean,
    val forwardAaaaDnsThroughTunnel: Boolean,
    val allowInet6Family: Boolean,
)
