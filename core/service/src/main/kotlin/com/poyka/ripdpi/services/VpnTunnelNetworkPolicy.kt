package com.poyka.ripdpi.services

import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import com.poyka.ripdpi.core.defaultTun2SocksTunnelMtu

data class VpnTunnelNetworkParameters(
    val tunnelMtu: Int = defaultTun2SocksTunnelMtu,
    val metered: Boolean = false,
)

internal object VpnTunnelNetworkPolicy {
    private const val TunnelMtuFloor = 1280
    private const val TunnelMtuCeiling = defaultTun2SocksTunnelMtu
    private const val TunnelEncapsulationOverheadBytes = 80

    fun parameters(
        linkProperties: LinkProperties?,
        capabilities: NetworkCapabilities?,
    ): VpnTunnelNetworkParameters =
        VpnTunnelNetworkParameters(
            tunnelMtu = tunnelMtu(linkProperties),
            metered = isMetered(capabilities),
        )

    fun tunnelMtu(linkProperties: LinkProperties?): Int {
        val linkMtu =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                linkProperties?.mtu?.takeIf { it > 0 }
            } else {
                null
            } ?: return defaultTun2SocksTunnelMtu
        return (linkMtu - TunnelEncapsulationOverheadBytes).coerceIn(TunnelMtuFloor, TunnelMtuCeiling)
    }

    fun isMetered(capabilities: NetworkCapabilities?): Boolean =
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
}
