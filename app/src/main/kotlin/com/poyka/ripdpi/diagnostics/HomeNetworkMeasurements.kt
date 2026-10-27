package com.poyka.ripdpi.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

internal const val HomeIpv6ConnectTimeoutMs = 1_500
private const val HomeIpv6ConnectPort = 443

// Blocking DNS, HTTP and socket calls ignore coroutine cancellation. They run outside the caller's job,
// so its deadline returns on time; a stuck call ends in the background at its own I/O timeout.
private val homeBlockingProbeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Runs blocking probe [block] on the IO pool; null when it fails or is not done within [timeoutMs]. */
internal suspend fun <T> awaitBlockingProbeWithin(
    timeoutMs: Long,
    block: suspend () -> T,
): T? {
    val work = homeBlockingProbeScope.async { runCatching { block() }.getOrNull() }
    return withTimeoutOrNull(timeoutMs) { work.await() }.also { if (it == null) work.cancel() }
}

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
