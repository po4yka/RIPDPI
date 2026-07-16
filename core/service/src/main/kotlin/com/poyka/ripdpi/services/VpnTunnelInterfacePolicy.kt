package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.normalizeDhtMitigationMode
import com.poyka.ripdpi.proto.AppSettings

internal fun vpnTunnelInterfacePolicySignature(
    settings: AppSettings,
    appRoutingPlan: VpnAppRoutingPlan,
    httpProxyPort: Int?,
): String {
    val (appRoutingMode, appRoutingPackages) =
        when (appRoutingPlan) {
            is VpnAppRoutingPlan.AllowOnly -> "allowOnly" to appRoutingPlan.packages
            is VpnAppRoutingPlan.Disallow -> "disallow" to appRoutingPlan.packages
        }
    return listOf(
        "ipv6=${settings.ipv6Enable}",
        "fullTunnel=${settings.fullTunnelMode}",
        "appRoutingMode=$appRoutingMode",
        "appRoutingPackages=${appRoutingPackages.sorted().joinToString(",")}",
        "httpProxyPort=${httpProxyPort ?: "off"}",
        "dhtMitigation=${normalizeDhtMitigationMode(settings.dhtMitigationMode)}",
    ).joinToString("|")
}
