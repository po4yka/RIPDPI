package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DiagnosticsNetworkEpoch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Callback evidence only; no binder/default-VPN epochs and no invented initial Ready state. */
internal class CandidatePhysicalNetworkObserver<T : Any>(
    private val requireBlockedObservation: Boolean,
) {
    private data class Entry(
        val preferred: Boolean,
        var capabilities: String? = null,
        var links: String? = null,
        var usableCapabilities: Boolean = false,
        var usableLinks: Boolean = false,
        var blocked: Boolean?,
    )

    private var registration = 0L
    private var activeRegistration: Long? = null
    private val entries = linkedMapOf<T, Entry>()
    private var selected: T? = null
    private val epoch = MutableStateFlow(0L)
    val changes = epoch.asStateFlow()

    @Synchronized fun beginRegistration(): Long {
        registration = Math.addExact(registration, 1)
        activeRegistration = registration
        entries.clear()
        selected = null
        advance()
        return registration
    }

    @Synchronized fun endRegistration(expected: Long) {
        if (activeRegistration != expected) return
        activeRegistration = null
        entries.clear()
        selected = null
        advance()
    }

    @Synchronized fun available(
        expected: Long,
        network: T,
        preferred: Boolean,
    ) {
        if (activeRegistration != expected) return
        advance()
        entries[network] = Entry(preferred, blocked = if (requireBlockedObservation) null else false)
        if (preferred || selected == null) selected = network
    }

    @Synchronized fun capabilities(
        expected: Long,
        network: T,
        fingerprint: String,
        usable: Boolean,
    ) {
        if (activeRegistration != expected) return
        val entry = entries[network]
        // Best-matching callbacks can repeat unchanged evidence after our own component changes.
        // Compare every stored field from this callback; legacy matching callbacks stay conservative.
        if (entry?.preferred == true && entry.capabilities == fingerprint && entry.usableCapabilities == usable) return
        advance()
        if (entry == null) return
        entry.capabilities = fingerprint
        entry.usableCapabilities = usable
    }

    @Synchronized fun links(
        expected: Long,
        network: T,
        fingerprint: String,
        usable: Boolean,
    ) {
        if (activeRegistration != expected) return
        val entry = entries[network]
        if (entry?.preferred == true && entry.links == fingerprint && entry.usableLinks == usable) return
        advance()
        if (entry == null) return
        entry.links = fingerprint
        entry.usableLinks = usable
    }

    @Synchronized fun blocked(
        expected: Long,
        network: T,
        blocked: Boolean,
    ) {
        if (activeRegistration != expected) return
        val entry = entries[network]
        if (entry?.preferred == true && entry.blocked == blocked) return
        advance()
        entry?.blocked = blocked
    }

    @Synchronized fun lost(
        expected: Long,
        network: T,
    ) {
        if (activeRegistration != expected) return
        advance()
        entries.remove(network)
        if (selected == network) selected = null
    }

    @Synchronized fun capture(): CandidatePhysicalNetworkToken? {
        val current = activeRegistration ?: return null
        val usable =
            if (selected != null) {
                selected?.takeIf { entries[it]?.ready() == true }
            } else {
                entries.entries.firstOrNull { it.value.ready() }?.key
            }
        return usable?.let { network ->
            selected = network
            val entry = checkNotNull(entries[network])
            CandidatePhysicalNetworkToken(
                current,
                epoch.value,
                network,
                checkNotNull(entry.capabilities) + ":" + checkNotNull(entry.links),
            )
        }
    }

    private fun Entry.ready() =
        capabilities != null && links != null && usableCapabilities && usableLinks && blocked == false

    private fun advance() {
        epoch.value = Math.addExact(epoch.value, 1)
    }
}

/** Transient and redacted. Tokens compare full callback evidence, including external A→B→A. */
class CandidatePhysicalNetworkToken internal constructor(
    private val registration: Long,
    private val eventEpoch: Long,
    private val network: Any,
    private val fingerprint: String,
) : DiagnosticsNetworkEpoch {
    override fun equals(other: Any?): Boolean =
        other is CandidatePhysicalNetworkToken &&
            registration == other.registration && eventEpoch == other.eventEpoch && network == other.network &&
            fingerprint == other.fingerprint

    override fun hashCode(): Int = arrayOf(registration, eventEpoch, network, fingerprint).contentHashCode()

    override fun toString() = "CandidatePhysicalNetworkToken([REDACTED])"
}
