package com.poyka.ripdpi.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

private const val SystemDnsPort = 53
private const val OtherTransportPriority = 3

/** Preflight only. Does not grant permission, change routes, or replace socket protection. */
@Singleton
class AndroidLocalNetworkAccess
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        fun hasAccess(): Boolean =
            Build.VERSION.SDK_INT < LocalNetworkPermissionApi ||
                ContextCompat.checkSelfPermission(context, LocalNetworkPermission) == PackageManager.PERMISSION_GRANTED

        suspend fun requireListener(host: String): Boolean {
            val addresses = resolveEndpoint(host.removeSurrounding("[", "]"), network = null)
            val required = addresses.any { !it.isLoopbackAddress }
            if (required && !hasAccess()) throw LocalNetworkAccessRequiredException()
            return required
        }

        suspend fun requireDirectEndpoint(
            host: String,
            port: Int,
        ): Boolean {
            if (host.isBlank()) return false
            val normalized = host.removeSurrounding("[", "]").trimEnd('.')
            val required =
                normalized.endsWith(".local", ignoreCase = true) ||
                    run {
                        val manager = context.getSystemService(ConnectivityManager::class.java)
                        val network = manager?.selectLocalNetworkUnderlay()
                        val properties = network?.let { manager.getLinkProperties(it) }
                        resolveEndpoint(normalized, network).any { address ->
                            val systemDns = port == SystemDnsPort && properties?.dnsServers?.contains(address) == true
                            val onLink =
                                properties?.routes?.any { route ->
                                    !route.isDefaultRoute &&
                                        route.gateway == null &&
                                        route.destination.contains(address)
                                } == true
                            !address.isLoopbackAddress && !systemDns &&
                                (LocalNetworkAddressPolicy.requiresPermission(address) || onLink)
                        }
                    }
            if (required && !hasAccess()) throw LocalNetworkAccessRequiredException()
            return required
        }

        private suspend fun resolveEndpoint(
            host: String,
            network: Network?,
        ): Array<InetAddress> =
            if (isNumericAddress(host)) {
                resolveEndpointBlocking(host, network)
            } else {
                withContext(Dispatchers.IO) { resolveEndpointBlocking(host, network) }
            }

        private fun resolveEndpointBlocking(
            host: String,
            network: Network?,
        ): Array<InetAddress> =
            try {
                if (isNumericAddress(host)) {
                    arrayOf(InetAddress.getByName(host))
                } else {
                    network?.getAllByName(host) ?: InetAddress.getAllByName(host)
                }
            } catch (_: UnknownHostException) {
                // DNS failure is not evidence of denied LAN access. The actual operation
                // still resolves its peer and reports its own error; no route is changed here.
                emptyArray()
            }

        private fun isNumericAddress(host: String): Boolean = ':' in host || host.all { it.isDigit() || it == '.' }
    }

@Suppress("DEPRECATION")
internal fun ConnectivityManager.selectLocalNetworkUnderlay(): Network? {
    fun capabilities(network: Network): NetworkCapabilities? = getNetworkCapabilities(network)

    fun isUnderlay(network: Network): Boolean =
        capabilities(network)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true

    fun priority(network: Network): Int =
        capabilities(network)?.let {
            when {
                it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 0
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
                it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                else -> OtherTransportPriority
            }
        } ?: Int.MAX_VALUE

    return activeNetwork?.takeIf(::isUnderlay) ?: allNetworks.filter(::isUnderlay).minByOrNull(::priority)
}
