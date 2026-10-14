package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.proto.AppSettings

/** Evaluates frozen transport input against the live installed-package set. */
internal suspend fun VpnCoordinatorHost.resolveTunnelInterfacePolicy(
    settings: AppSettings,
    packageRoutingRules: List<PackageRoutingRule>,
    installedPackages: Set<String>,
): ResolvedVpnInterfacePolicy {
    val appRoutingPlan = resolveAppRoutingPlan(settings, packageRoutingRules, installedPackages)
    // A VPN session never advertises an HTTP proxy through VpnService.Builder.setHttpProxy,
    // even with append_http_proxy on. The only local listener the session owns binds an
    // ephemeral port and requires the per-session token (VpnRuntimeCompositionCoordinator),
    // and ProxyInfo cannot carry credentials, so system-proxy clients cannot use it. The
    // configured settings port is not bound in VPN mode: advertising it sends proxied
    // requests to whatever local app binds that port. The TUN already captures the traffic.
    val httpProxyPort: Int? = null
    return ResolvedVpnInterfacePolicy(
        appRoutingPlan = appRoutingPlan,
        httpProxyPort = httpProxyPort,
        signature = vpnTunnelInterfacePolicySignature(settings, appRoutingPlan, httpProxyPort),
    )
}

internal data class ResolvedVpnInterfacePolicy(
    val appRoutingPlan: VpnAppRoutingPlan,
    val httpProxyPort: Int?,
    val signature: String,
)

internal suspend fun VpnCoordinatorHost.profileTunnelNetworkParameters(
    profile: VpnProfileInterface?,
): VpnTunnelNetworkParameters =
    currentTunnelNetworkParameters().let { parameters ->
        profile?.let { parameters.copy(tunnelMtu = it.mtu) }
            ?: parameters
    }
