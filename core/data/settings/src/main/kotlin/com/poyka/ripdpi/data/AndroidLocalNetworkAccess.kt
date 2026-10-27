package com.poyka.ripdpi.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

private const val SystemDnsPort = 53
private const val OtherTransportPriority = 3
private const val HostnameLookupBudgetMillis = 2_000L
private const val MaxConcurrentHostnameLookups = 2

/** Preflight only. Does not grant permission, change routes, or replace socket protection. */
@Singleton
class AndroidLocalNetworkAccess
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        // Lookups run detached: getaddrinfo cannot be interrupted, so a caller must not wait for it.
        // The parallelism cap keeps lookups stuck on a dropped resolver from taking over Dispatchers.IO.
        private val lookupScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(MaxConcurrentHostnameLookups))

        fun hasAccess(): Boolean =
            Build.VERSION.SDK_INT < LocalNetworkPermissionApi ||
                ContextCompat.checkSelfPermission(context, LocalNetworkPermission) == PackageManager.PERMISSION_GRANTED

        suspend fun requireListener(host: String): Boolean {
            val addresses = resolveEndpoint(host.removeSurrounding("[", "]"), network = null)
            // An unanswered lookup is unknown, not "not local": ask for the permission.
            val required = addresses?.any { !it.isLoopbackAddress } ?: true
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
                        resolveEndpoint(normalized, network)?.any { address ->
                            val systemDns = port == SystemDnsPort && properties?.dnsServers?.contains(address) == true
                            val onLink =
                                properties?.routes?.any { route ->
                                    !route.isDefaultRoute &&
                                        route.gateway == null &&
                                        route.destination.contains(address)
                                } == true
                            !address.isLoopbackAddress && !systemDns &&
                                (LocalNetworkAddressPolicy.requiresPermission(address) || onLink)
                        } ?: true // An unanswered lookup is unknown, not "not local": ask for the permission.
                    }
            if (required && !hasAccess()) throw LocalNetworkAccessRequiredException()
            return required
        }

        private suspend fun resolveEndpoint(
            host: String,
            network: Network?,
        ): Array<InetAddress>? =
            if (isNumericAddress(host)) {
                resolveEndpointBlocking(host, network)
            } else {
                resolveWithinBudget(lookupScope, HostnameLookupBudgetMillis) { resolveEndpointBlocking(host, network) }
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

/**
 * Runs a blocking [lookup] detached in [scope] and waits at most [budgetMillis]. A lookup that does
 * not answer in time (dropped DNS on a censored network, a slow cold or handover lookup) gives null,
 * "unknown", and a cancelled caller returns at once instead of after the resolver timeout.
 */
internal suspend fun resolveWithinBudget(
    scope: CoroutineScope,
    budgetMillis: Long,
    lookup: () -> Array<InetAddress>,
): Array<InetAddress>? {
    val pending = scope.async { lookup() }
    return withTimeoutOrNull(budgetMillis) { pending.await() }
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
