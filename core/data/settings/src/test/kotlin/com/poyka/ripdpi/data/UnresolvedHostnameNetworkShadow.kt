package com.poyka.ripdpi.data

import android.net.Network
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNetwork
import java.net.InetAddress
import java.net.UnknownHostException

@Implements(Network::class)
class UnresolvedHostnameNetworkShadow : ShadowNetwork() {
    val lookups = mutableListOf<String>()

    @Implementation
    fun getAllByName(host: String): Array<InetAddress> {
        lookups += host
        throw UnknownHostException(host)
    }
}
