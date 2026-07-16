package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.effectiveAppRoutingEnabledPresetIds
import com.poyka.ripdpi.data.normalizeAppRoutingPolicyMode
import com.poyka.ripdpi.data.normalizeDhtMitigationMode
import com.poyka.ripdpi.proto.AppSettings

internal fun vpnTunnelInterfacePolicySignature(settings: AppSettings): String {
    val appRoutingPresets =
        settings
            .effectiveAppRoutingEnabledPresetIds()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .sorted()
            .joinToString(",")
    val splitTunnelMode =
        if (settings.fullTunnelMode) {
            SplitTunnelMode.Off
        } else {
            settings.splitTunnelMode
                .takeIf { it == SplitTunnelMode.Include || it == SplitTunnelMode.Exclude }
                ?: SplitTunnelMode.Off
        }
    val splitTunnelPackages =
        if (splitTunnelMode == SplitTunnelMode.Off) {
            ""
        } else {
            settings.splitTunnelPackagesList
                .asSequence()
                .filter(String::isNotEmpty)
                .distinct()
                .sorted()
                .joinToString(",")
        }
    return listOf(
        "ipv6=${settings.ipv6Enable}",
        "fullTunnel=${settings.fullTunnelMode}",
        "splitTunnelMode=$splitTunnelMode",
        "splitTunnelPackages=$splitTunnelPackages",
        "appRoutingMode=${normalizeAppRoutingPolicyMode(settings.appRoutingPolicyMode)}",
        "appRoutingPresets=$appRoutingPresets",
        "dhtMitigation=${normalizeDhtMitigationMode(settings.dhtMitigationMode)}",
    ).joinToString("|")
}
