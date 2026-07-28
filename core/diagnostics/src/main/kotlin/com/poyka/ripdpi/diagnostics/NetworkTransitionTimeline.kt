package com.poyka.ripdpi.diagnostics

import android.net.NetworkCapabilities
import android.os.SystemClock
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
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

/** Serializes callback capture with terminal deactivation so a barrier cannot overtake a callback. */
internal class NetworkTransitionSessionGate {
    private val lock = Any()
    private var connectionSessionId: String? = null

    fun activate(connectionSessionId: String) {
        synchronized(lock) {
            this.connectionSessionId = connectionSessionId
        }
    }

    fun deactivate() {
        synchronized(lock) {
            connectionSessionId = null
        }
    }

    fun capture(enqueue: (String) -> Boolean): Boolean? =
        synchronized(lock) {
            connectionSessionId?.let(enqueue)
        }
}

/**
 * Single-consumer bounded callback lane. Callback producers use [Channel.trySend], so they never
 * block ConnectivityManager's callback thread. Terminal consumers can enqueue a barrier with a
 * bounded wait; later callback events cannot displace that barrier.
 */
internal class NetworkTransitionTimeline(
    scope: CoroutineScope,
    private val clock: NetworkTransitionClock = AndroidNetworkTransitionClock,
    private val enqueueForActiveSession: (((String) -> Boolean) -> Boolean?),
    private val persist: suspend (NetworkTransitionEvent) -> Unit,
) {
    private val generations = NetworkTransitionGenerationTracker()
    private val sequence = AtomicLong()
    private val captureHealthy = AtomicBoolean(true)
    private val sessionEventCounts = LinkedHashMap<String, Int>()
    private val queue = Channel<NetworkTransitionCommand>(capacity = MaxBufferedNetworkTransitions)

    init {
        scope.launch {
            var persistenceHealthy = true
            for (command in queue) {
                when (command) {
                    is NetworkTransitionCommand.Persist -> {
                        val failure = runCatching { persist(command.event) }.exceptionOrNull() ?: continue
                        when (failure) {
                            is CancellationException -> {
                                throw failure
                            }

                            is Exception -> {
                                persistenceHealthy = false
                                Logger.e(failure) { "Network transition persistence failed" }
                            }

                            else -> {
                                throw failure
                            }
                        }
                    }

                    is NetworkTransitionCommand.Barrier -> {
                        val captureWasHealthy = captureHealthy.getAndSet(true)
                        command.result.complete(persistenceHealthy && captureWasHealthy)
                        persistenceHealthy = true
                    }
                }
            }
        }
    }

    internal suspend fun flush(timeoutMillis: Long = NetworkTransitionFlushTimeoutMillis): Boolean {
        val result = CompletableDeferred<Boolean>()
        val boundedTimeout = timeoutMillis.coerceIn(1L, NetworkTransitionFlushTimeoutMillis)
        return withTimeoutOrNull(boundedTimeout) {
            queue.send(NetworkTransitionCommand.Barrier(result))
            result.await()
        } ?: false
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
        val captured =
            enqueueForActiveSession { connectionSessionId ->
                if (!reserveSessionEvent(connectionSessionId)) {
                    false
                } else {
                    val timestamp = clock.capture()
                    queue
                        .trySend(
                            NetworkTransitionCommand.Persist(
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
                            ),
                        ).isSuccess
                }
            }
        if (captured == false) captureHealthy.set(false)
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

private sealed interface NetworkTransitionCommand {
    data class Persist(
        val event: NetworkTransitionEvent,
    ) : NetworkTransitionCommand

    data class Barrier(
        val result: CompletableDeferred<Boolean>,
    ) : NetworkTransitionCommand
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
internal const val NetworkTransitionFlushTimeoutMillis = 2_000L
private const val MaxTrackedNetworkKeys = 64
internal const val MaxPersistedNetworkTransitionsPerSession = 64
private const val ImminentLosingDeadlineMs = 1_000
private const val NearLosingDeadlineMs = 10_000
