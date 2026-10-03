package com.poyka.ripdpi.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.poyka.ripdpi.data.AuthoritativeVpnUnderlayObservationProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Transient callback generation; changing away and back never revalidates old payload evidence. */
@Singleton
class CandidateRelayNetworkEpoch internal constructor(
    register: (() -> Unit) -> Boolean,
    private val available: () -> Boolean,
    private val underlayGeneration: () -> Long,
) {
    private val generation = AtomicLong()
    private val registered = register { generation.incrementAndGet() }

    @Inject
    constructor(
        @ApplicationContext context: Context,
        underlay: AuthoritativeVpnUnderlayObservationProvider,
    ) : this(
        register = { changed -> registerCandidateNetworkCallback(context, changed) },
        available = { hasActiveCandidateNetwork(context) },
        underlayGeneration = { underlay.changes.value },
    )

    /** Returns only an opaque counter, never a platform Network handle. */
    fun capture(): Pair<Long, Long>? = if (registered && available()) generation.get() to underlayGeneration() else null
}

@android.annotation.SuppressLint("MissingPermission")
private fun hasActiveCandidateNetwork(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return manager.activeNetwork != null
}

@android.annotation.SuppressLint("MissingPermission")
private fun registerCandidateNetworkCallback(
    context: Context,
    changed: () -> Unit,
): Boolean =
    try {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = changed()

                override fun onLost(network: Network) = changed()

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) = changed()

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties,
                ) = changed()
            },
        )
        true
    } catch (_: RuntimeException) {
        false
    }
