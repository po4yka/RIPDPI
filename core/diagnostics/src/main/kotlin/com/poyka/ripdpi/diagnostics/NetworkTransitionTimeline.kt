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
import java.util.LinkedHashSet
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

internal data class NetworkTransitionAdmission(
    val connectionSessionId: String,
    val epoch: Long,
)

/** Serializes complete callback admission with terminal deactivation. */
internal class NetworkTransitionSessionGate {
    private val lock = Any()
    private var nextEpoch = 0L
    private var admission: NetworkTransitionAdmission? = null

    fun activate(connectionSessionId: String): NetworkTransitionAdmission =
        synchronized(lock) {
            NetworkTransitionAdmission(connectionSessionId, ++nextEpoch).also { admission = it }
        }

    fun deactivate(): NetworkTransitionAdmission? =
        synchronized(lock) {
            admission.also { admission = null }
        }

    fun restore(admission: NetworkTransitionAdmission) {
        synchronized(lock) {
            check(this.admission == null) { "network transition session already active" }
            this.admission = admission
        }
    }

    fun withAdmission(block: (NetworkTransitionAdmission?) -> Unit) {
        synchronized(lock) { block(admission) }
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
    private val withSessionAdmission: (((NetworkTransitionAdmission?) -> Unit) -> Unit),
    private val persist: suspend (NetworkTransitionEvent) -> Unit,
) {
    private val generations = NetworkTransitionGenerationTracker()
    private val sequence = AtomicLong()
    private val captureUnhealthyEpochs = LinkedHashSet<Long>()
    private val sessionEventCounts = LinkedHashMap<String, Int>()
    private val queue = Channel<NetworkTransitionCommand>(capacity = MaxBufferedNetworkTransitions)

    init {
        scope.launch {
            val persistenceUnhealthyEpochs = LinkedHashSet<Long>()
            for (command in queue) {
                when (command) {
                    is NetworkTransitionCommand.Persist -> {
                        val failure = runCatching { persist(command.event) }.exceptionOrNull() ?: continue
                        when (failure) {
                            is CancellationException -> {
                                throw failure
                            }

                            is Exception -> {
                                persistenceUnhealthyEpochs.addBounded(command.admission.epoch)
                                Logger.e(failure) { "Network transition persistence failed" }
                            }

                            else -> {
                                throw failure
                            }
                        }
                    }

                    is NetworkTransitionCommand.Barrier -> {
                        synchronized(command) {
                            if (command.active) {
                                command.active = false
                                val captureWasHealthy = clearCaptureFailure(command.admission.epoch)
                                val persistenceWasHealthy =
                                    persistenceUnhealthyEpochs.remove(command.admission.epoch).not()
                                command.result.complete(captureWasHealthy && persistenceWasHealthy)
                            } else {
                                command.result.complete(false)
                            }
                        }
                    }
                }
            }
        }
    }

    internal suspend fun flush(
        admission: NetworkTransitionAdmission,
        timeoutMillis: Long = NetworkTransitionFlushTimeoutMillis,
    ): Boolean {
        val result = CompletableDeferred<Boolean>()
        val barrier = NetworkTransitionCommand.Barrier(admission, result)
        val boundedTimeout = timeoutMillis.coerceIn(1L, NetworkTransitionFlushTimeoutMillis)
        var completed = false
        return try {
            withTimeoutOrNull(boundedTimeout) {
                queue.send(barrier)
                result.await().also { completed = true }
            } ?: false
        } finally {
            if (!completed) {
                synchronized(barrier) { barrier.active = false }
            }
        }
    }

    fun recordAvailable(networkKey: Any) {
        withSessionAdmission { admission ->
            val generation = generations.replaceGeneration(networkKey)
            recordGeneration(admission, generation, NetworkTransitionKind.Available)
        }
    }

    fun recordLosing(
        networkKey: Any,
        maxMsToLive: Int,
    ) {
        withSessionAdmission { admission ->
            val band =
                when {
                    maxMsToLive <= ImminentLosingDeadlineMs -> NetworkLosingDeadlineBand.Imminent
                    maxMsToLive <= NearLosingDeadlineMs -> NetworkLosingDeadlineBand.Near
                    else -> NetworkLosingDeadlineBand.Later
                }
            recordKnown(admission, networkKey, NetworkTransitionKind.Losing, losingDeadlineBand = band)
        }
    }

    fun recordLost(networkKey: Any) {
        withSessionAdmission { admission ->
            val generation = generations.removeGeneration(networkKey) ?: return@withSessionAdmission
            recordGeneration(admission, generation, NetworkTransitionKind.Lost)
        }
    }

    fun recordCapabilities(
        networkKey: Any,
        capabilities: NetworkCapabilities,
    ) {
        withSessionAdmission { admission ->
            recordKnown(
                admission = admission,
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
    }

    fun recordLinkProperties(networkKey: Any) {
        withSessionAdmission { admission ->
            recordKnown(admission, networkKey, NetworkTransitionKind.LinkPropertiesChanged)
        }
    }

    private fun recordKnown(
        admission: NetworkTransitionAdmission?,
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
            admission = admission,
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
        admission: NetworkTransitionAdmission?,
        generation: Long,
        kind: NetworkTransitionKind,
        path: NetworkTransitionPath? = null,
        internet: NetworkTransitionState? = null,
        validated: NetworkTransitionState? = null,
        captivePortal: NetworkTransitionState? = null,
        losingDeadlineBand: NetworkLosingDeadlineBand? = null,
    ) {
        admission ?: return
        val captured =
            if (!reserveSessionEvent(admission.connectionSessionId)) {
                false
            } else {
                val timestamp = clock.capture()
                queue
                    .trySend(
                        NetworkTransitionCommand.Persist(
                            admission = admission,
                            event =
                                NetworkTransitionEvent(
                                    connectionSessionId = admission.connectionSessionId,
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
        if (!captured) markCaptureFailure(admission.epoch)
    }

    @Synchronized
    private fun markCaptureFailure(epoch: Long) {
        captureUnhealthyEpochs.addBounded(epoch)
    }

    @Synchronized
    private fun clearCaptureFailure(epoch: Long): Boolean = captureUnhealthyEpochs.remove(epoch).not()

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
        val admission: NetworkTransitionAdmission,
        val event: NetworkTransitionEvent,
    ) : NetworkTransitionCommand

    class Barrier(
        val admission: NetworkTransitionAdmission,
        val result: CompletableDeferred<Boolean>,
        var active: Boolean = true,
    ) : NetworkTransitionCommand
}

private fun LinkedHashSet<Long>.addBounded(epoch: Long) {
    add(epoch)
    while (size > MaxTrackedNetworkKeys) {
        val iterator = iterator()
        if (iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
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
internal const val NetworkTransitionFlushTimeoutMillis = 2_000L
private const val MaxTrackedNetworkKeys = 64
internal const val MaxPersistedNetworkTransitionsPerSession = 64
private const val ImminentLosingDeadlineMs = 1_000
private const val NearLosingDeadlineMs = 10_000
