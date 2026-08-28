package com.poyka.ripdpi.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Tracks only this probe's passive observations; never changes the VPN's underlay authority. */
internal class SshProbeUnderlaySelection<T : Any> {
    private val candidates = linkedMapOf<T, Long>()
    private var selected: Pair<T, Long>? = null
    private var generation = 0L
    private var closed = false
    private val signal = MutableStateFlow(Signal<T>())

    @Synchronized
    fun update(
        network: T,
        networkGeneration: Long?,
    ) {
        if (closed) return
        if (networkGeneration == null) candidates.remove(network) else candidates[network] = networkGeneration
        val next =
            selected?.first?.let { candidate -> candidates[candidate]?.let { candidate to it } }
                ?: candidates.entries.firstOrNull()?.let { it.key to it.value }
        if (next == selected) return
        selected = next
        generation += 1
        signal.value = Signal(snapshot())
    }

    @Synchronized
    fun snapshot(): ResolverUnderlaySnapshot<T>? = selected?.let { ResolverUnderlaySnapshot(it.first, generation) }

    suspend fun awaitEligible(): ResolverUnderlaySnapshot<T>? =
        signal.first { it.closed || it.snapshot != null }.snapshot

    @Synchronized
    fun close() {
        closed = true
        candidates.clear()
        selected = null
        generation += 1
        signal.value = Signal(closed = true)
    }

    private data class Signal<T>(
        val snapshot: ResolverUnderlaySnapshot<T>? = null,
        val closed: Boolean = false,
    )
}
