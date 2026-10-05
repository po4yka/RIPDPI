package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.routing.PackageRoutingRule
import com.poyka.ripdpi.data.toSettingsSections
import com.poyka.ripdpi.proto.AppSettings

/** Evaluates frozen transport input against the live installed-package set. */
internal suspend fun VpnCoordinatorHost.resolveTunnelInterfacePolicy(
    settings: AppSettings,
    packageRoutingRules: List<PackageRoutingRule>,
    installedPackages: Set<String>,
): ResolvedVpnInterfacePolicy {
    val appRoutingPlan = resolveAppRoutingPlan(settings, packageRoutingRules, installedPackages)
    val proxy = settings.toSettingsSections().proxy
    val httpProxyPort =
        if (proxy.appendHttpProxy) effectiveListenerPort(proxy) else null
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
