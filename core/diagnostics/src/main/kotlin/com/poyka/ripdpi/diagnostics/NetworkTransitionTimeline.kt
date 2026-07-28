package com.poyka.ripdpi.diagnostics

import android.net.NetworkCapabilities
import android.os.SystemClock
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

internal enum class NetworkTransitionKind(
    val wireValue: String,
) {
    Available("available"),
    Losing("losing"),
    Lost("lost"),
    CapabilitiesChanged("capabilities_changed"),
    LinkPropertiesChanged("link_properties_changed"),
}

internal enum class NetworkTransitionPath(
    val wireValue: String,
) {
    Vpn("vpn"),
    NonVpn("non_vpn"),
}

internal enum class NetworkTransitionState(
    val wireValue: String,
) {
    Present("present"),
    Absent("absent"),
}

internal enum class NetworkLosingDeadlineBand(
    val wireValue: String,
) {
    Imminent("imminent"),
    Near("near"),
    Later("later"),
}

/** Privacy-safe callback fact. Network handles and link-layer values never cross this boundary. */
internal data class NetworkTransitionEvent(
    val connectionSessionId: String,
    val generation: Long,
    val sequence: Long,
    val elapsedRealtimeMs: Long,
    val occurredAtEpochMs: Long,
    val kind: NetworkTransitionKind,
    val path: NetworkTransitionPath? = null,
    val internet: NetworkTransitionState? = null,
    val validated: NetworkTransitionState? = null,
    val captivePortal: NetworkTransitionState? = null,
    val losingDeadlineBand: NetworkLosingDeadlineBand? = null,
)

internal fun NetworkTransitionEvent.toRedactedMessage(): String =
    buildList {
        add("kind=${kind.wireValue}")
        path?.let { add("path=${it.wireValue}") }
        internet?.let { add("internet=${it.wireValue}") }
        validated?.let { add("validated=${it.wireValue}") }
        captivePortal?.let { add("captive_portal=${it.wireValue}") }
        losingDeadlineBand?.let { add("losing_deadline=${it.wireValue}") }
        add("generation=$generation")
        add("sequence=$sequence")
    }.joinToString(separator = ";")

internal fun interface NetworkTransitionClock {
    fun capture(): NetworkTransitionTimestamp
}

internal data class NetworkTransitionTimestamp(
    val elapsedRealtimeMs: Long,
    val epochMs: Long,
)

private object AndroidNetworkTransitionClock : NetworkTransitionClock {
    override fun capture(): NetworkTransitionTimestamp =
        NetworkTransitionTimestamp(
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            epochMs = System.currentTimeMillis(),
        )
}

/**
 * Single-consumer bounded callback lane. DROP_OLDEST preserves the most recent transition context
 * without ever blocking ConnectivityManager's callback thread.
 */
internal class NetworkTransitionTimeline(
    scope: CoroutineScope,
    private val clock: NetworkTransitionClock = AndroidNetworkTransitionClock,
    private val connectionSessionIdProvider: () -> String?,
    private val persist: suspend (NetworkTransitionEvent) -> Unit,
) {
    private val generations = NetworkTransitionGenerationTracker()
    private val sequence = AtomicLong()
    private val sessionEventCounts = LinkedHashMap<String, Int>()
    private val queue =
        Channel<NetworkTransitionEvent>(
            capacity = MaxBufferedNetworkTransitions,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    init {
        scope.launch {
            for (event in queue) {
                val failure = runCatching { persist(event) }.exceptionOrNull() ?: continue
                when (failure) {
                    is CancellationException -> throw failure
                    is Exception -> Logger.e(failure) { "Network transition persistence failed" }
                    else -> throw failure
                }
            }
        }
    }

    fun recordAvailable(networkKey: Any) {
        val generation = generations.replaceGeneration(networkKey)
        recordGeneration(generation, NetworkTransitionKind.Available)
    }

    fun recordLosing(
        networkKey: Any,
        maxMsToLive: Int,
    ) {
        val band =
            when {
                maxMsToLive <= ImminentLosingDeadlineMs -> NetworkLosingDeadlineBand.Imminent
                maxMsToLive <= NearLosingDeadlineMs -> NetworkLosingDeadlineBand.Near
                else -> NetworkLosingDeadlineBand.Later
            }
        recordKnown(networkKey, NetworkTransitionKind.Losing, losingDeadlineBand = band)
    }

    fun recordLost(networkKey: Any) {
        val generation = generations.removeGeneration(networkKey) ?: return
        recordGeneration(generation, NetworkTransitionKind.Lost)
    }

    fun recordCapabilities(
        networkKey: Any,
        capabilities: NetworkCapabilities,
    ) {
        recordKnown(
            networkKey = networkKey,
            kind = NetworkTransitionKind.CapabilitiesChanged,
            path =
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    NetworkTransitionPath.Vpn
                } else {
                    NetworkTransitionPath.NonVpn
                },
            internet = capabilities.stateOf(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = capabilities.stateOf(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captivePortal = capabilities.stateOf(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
        )
    }

    fun recordLinkProperties(networkKey: Any) {
        recordKnown(networkKey, NetworkTransitionKind.LinkPropertiesChanged)
    }

    private fun recordKnown(
        networkKey: Any,
        kind: NetworkTransitionKind,
        path: NetworkTransitionPath? = null,
        internet: NetworkTransitionState? = null,
        validated: NetworkTransitionState? = null,
        captivePortal: NetworkTransitionState? = null,
        losingDeadlineBand: NetworkLosingDeadlineBand? = null,
    ) {
        val generation = generations.existingGeneration(networkKey) ?: return
        recordGeneration(
            generation = generation,
            kind = kind,
            path = path,
            internet = internet,
            validated = validated,
            captivePortal = captivePortal,
            losingDeadlineBand = losingDeadlineBand,
        )
    }

    private fun recordGeneration(
        generation: Long,
        kind: NetworkTransitionKind,
        path: NetworkTransitionPath? = null,
        internet: NetworkTransitionState? = null,
        validated: NetworkTransitionState? = null,
        captivePortal: NetworkTransitionState? = null,
        losingDeadlineBand: NetworkLosingDeadlineBand? = null,
    ) {
        val connectionSessionId = connectionSessionIdProvider() ?: return
        if (!reserveSessionEvent(connectionSessionId)) return
        val timestamp = clock.capture()
        queue.trySend(
            NetworkTransitionEvent(
                connectionSessionId = connectionSessionId,
                generation = generation,
                sequence = sequence.incrementAndGet(),
                elapsedRealtimeMs = timestamp.elapsedRealtimeMs,
                occurredAtEpochMs = timestamp.epochMs,
                kind = kind,
                path = path,
                internet = internet,
                validated = validated,
                captivePortal = captivePortal,
                losingDeadlineBand = losingDeadlineBand,
            ),
        )
    }

    @Synchronized
    private fun reserveSessionEvent(connectionSessionId: String): Boolean {
        val current = sessionEventCounts[connectionSessionId] ?: 0
        if (current >= MaxPersistedNetworkTransitionsPerSession) return false
        sessionEventCounts[connectionSessionId] = current + 1
        while (sessionEventCounts.size > MaxTrackedNetworkKeys) {
            val iterator = sessionEventCounts.entries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        return true
    }
}

private class NetworkTransitionGenerationTracker {
    private val generations = LinkedHashMap<Any, Long>()
    private var nextGeneration = 0L

    @Synchronized
    fun replaceGeneration(key: Any): Long {
        val generation = ++nextGeneration
        generations[key] = generation
        while (generations.size > MaxTrackedNetworkKeys) {
            val iterator = generations.entries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        return generation
    }

    @Synchronized
    fun existingGeneration(key: Any): Long? = generations[key]

    @Synchronized
    fun removeGeneration(key: Any): Long? = generations.remove(key)
}

private fun NetworkCapabilities.stateOf(capability: Int): NetworkTransitionState =
    if (hasCapability(capability)) NetworkTransitionState.Present else NetworkTransitionState.Absent

internal const val MaxBufferedNetworkTransitions = 64
private const val MaxTrackedNetworkKeys = 64
internal const val MaxPersistedNetworkTransitionsPerSession = 64
private const val ImminentLosingDeadlineMs = 1_000
private const val NearLosingDeadlineMs = 10_000
