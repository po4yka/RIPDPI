package com.poyka.ripdpi.diagnostics

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

internal const val HomeIpv6ConnectTimeoutMs = 1_500
private const val HomeIpv6ConnectPort = 443

internal fun measureHomeIpv6Reachability(
    socketFactory: (() -> Socket)?,
    isNetworkCurrent: () -> Boolean,
): Boolean? {
    if (socketFactory == null || !isNetworkCurrent()) return null
    val reachable =
        runCatching {
            // Numeric control address: no DNS success can substitute for connection evidence.
            val target = InetAddress.getByName("2606:4700:4700::1111")
            socketFactory().use { socket ->
                socket.connect(InetSocketAddress(target, HomeIpv6ConnectPort), HomeIpv6ConnectTimeoutMs)
            }
            true
        }.getOrDefault(false)
    return reachable.takeIf { isNetworkCurrent() }
}

internal fun homeSystemResolver(dnsServers: List<InetAddress>?): String? = dnsServers?.firstOrNull()?.hostAddress
