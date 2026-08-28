package com.poyka.ripdpi.services

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/** Passive physical-network callbacks owned solely by one observation, including under an existing VPN. */
internal class SshProbeUnderlayMonitor(
    context: Context,
) : AutoCloseable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val entries = mutableMapOf<Network, Entry>()
    private val selection = SshProbeUnderlaySelection<Network>()
    private var closed = false
    private var registered = false
    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = available(network)

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) = update(network) { it.authority.onCapabilitiesChanged(it.epoch, network, capabilities) }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) = update(network) { it.authority.onLinkPropertiesChanged(it.epoch, network, linkProperties) }

            override fun onLost(network: Network) = lost(network)
        }

    @SuppressLint("MissingPermission") // Observer checks ACCESS_NETWORK_STATE before constructing the monitor.
    @Synchronized
    fun start() {
        check(!closed && !registered)
        registered = true
        connectivity.registerNetworkCallback(
            NetworkRequest
                .Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build(),
            callback,
        )
    }

    fun snapshot(): ResolverUnderlaySnapshot<Network>? = selection.snapshot()

    suspend fun awaitEligible(): ResolverUnderlaySnapshot<Network>? = selection.awaitEligible()

    @Synchronized
    private fun available(network: Network) {
        if (closed || network in entries) return
        val authority = DirectDnsUnderlayAuthority()
        val epoch = authority.beginCallbackEpoch()
        authority.onAvailable(epoch, network)
        entries[network] = Entry(authority, epoch)
    }

    @Synchronized
    private fun update(
        network: Network,
        apply: (Entry) -> Unit,
    ) {
        if (closed) return
        val entry = entries[network] ?: return
        apply(entry)
        selection.update(network, entry.authority.snapshot(entry.epoch)?.generation)
    }

    @Synchronized
    private fun lost(network: Network) {
        entries.remove(network)?.let { it.authority.endCallbackEpoch(it.epoch) }
        selection.update(network, null)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        entries.values.forEach { it.authority.endCallbackEpoch(it.epoch) }
        entries.clear()
        selection.close()
        if (registered) {
            try {
                connectivity.unregisterNetworkCallback(callback)
            } catch (_: IllegalArgumentException) {
                // Registration may have failed; never unregister a different observer.
            }
        }
    }

    private data class Entry(
        val authority: DirectDnsUnderlayAuthority,
        val epoch: Long,
    )
}
