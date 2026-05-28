package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsSettings

internal data class VpnTunnelDnsPlan(
    val builderDnsAddress: String,
    val resolverDns: ActiveDnsSettings,
    val mapDnsEnabled: Boolean,
    val routeDnsThroughSocks5: Boolean,
)

internal fun vpnTunnelDnsPlan(
    activeDns: ActiveDnsSettings,
    forceTunnelDns: Boolean,
): VpnTunnelDnsPlan {
    val resolverDns =
        if (forceTunnelDns && activeDns.mode != DnsModeEncrypted) {
            canonicalDefaultEncryptedDnsSettings()
        } else {
            activeDns
        }
    return VpnTunnelDnsPlan(
        builderDnsAddress =
            vpnDnsAddressForProfile(
                dnsMode = activeDns.mode,
                plainDnsIp = activeDns.dnsIp,
                forceTunnelDns = forceTunnelDns,
            ),
        resolverDns = resolverDns,
        mapDnsEnabled = resolverDns.mode == DnsModeEncrypted,
        routeDnsThroughSocks5 = forceTunnelDns,
    )
}
