package com.poyka.ripdpi.data

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

const val LocalNetworkPermission = "android.permission.ACCESS_LOCAL_NETWORK"
const val LocalNetworkPermissionApi = 37

/** Classification of an actual Android socket peer, never an address carried inside a remote tunnel. */
object LocalNetworkAddressPolicy {
    private const val UlaMask = 0xfe
    private const val UlaPrefix = 0xfc

    fun requiresPermission(address: InetAddress): Boolean =
        when {
            address.isLoopbackAddress -> false
            address.isAnyLocalAddress || address.isMulticastAddress -> true
            address.isLinkLocalAddress || address.isSiteLocalAddress -> true
            address is Inet6Address -> (address.address[0].toInt() and UlaMask) == UlaPrefix
            address is Inet4Address -> address.address.all { it == (-1).toByte() }
            else -> false
        }
}
