package com.poyka.ripdpi.e2e

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.util.Log

private const val DefaultNetworkStateVpn = 1
private const val DefaultNetworkStateNonVpn = 2
private const val DefaultNetworkStateUnknown = 3

internal fun putDefaultNetworkState(
    extras: Bundle,
    key: String,
    state: Int,
) {
    extras.putInt(key, state)
}

internal fun defaultNetworkStateCode(context: Context?): Int {
    val connectivityManager =
        context?.getSystemService(ConnectivityManager::class.java)
            ?: return DefaultNetworkStateUnknown
    val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
    return when {
        capabilities == null -> DefaultNetworkStateUnknown

        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) -> DefaultNetworkStateVpn

        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) -> DefaultNetworkStateNonVpn

        else -> DefaultNetworkStateUnknown
    }
}

internal fun logDnsProbeSocket(
    phase: String,
    context: Context?,
    requestId: Int,
    remoteHost: String?,
    remotePort: Int,
    extras: Bundle,
) {
    // Use Java-style try/catch: the standalone test UID has no Kotlin runtime.
    try {
        var networkState = DefaultNetworkStateUnknown
        try {
            networkState = defaultNetworkStateCode(context)
        } catch (_: Throwable) {
            // Keep the state unknown when the platform lookup fails.
        }
        val localPort = if (extras.containsKey(ExtraLocalPort)) extras.getInt(ExtraLocalPort) else null
        Log.i(
            "TestDnsProbe",
            "phase=$phase observedAt=${SystemClock.elapsedRealtime()} queryId=$requestId " +
                "local=${extras.getString(ExtraLocalAddress)}:$localPort " +
                "remote=$remoteHost:$remotePort defaultNetworkState=$networkState",
        )
    } catch (_: Throwable) {
        // A diagnostic failure must not change the probe result.
    }
}
