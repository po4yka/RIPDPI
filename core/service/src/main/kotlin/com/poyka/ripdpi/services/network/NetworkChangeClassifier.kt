package com.poyka.ripdpi.services.network

/** Classifies which policy dimensions changed between two [NetworkSnapshot]s. */
object NetworkChangeClassifier {
    /**
     * Returns the set of [ScopedPolicyChange] dimensions that differ between [prev] and [next].
     * Returns an empty set when [prev] is null (first snapshot — nothing to diff against)
     * or when no fields changed.
     */
    fun classify(
        prev: NetworkSnapshot?,
        next: NetworkSnapshot,
    ): Set<ScopedPolicyChange> {
        if (prev == null) return emptySet()
        return buildSet {
            if (prev.dnsServers != next.dnsServers) add(ScopedPolicyChange.DnsChange)
            if (prev.hasDefaultRoute != next.hasDefaultRoute) add(ScopedPolicyChange.RouteChange)
            if (prev.metered != next.metered) add(ScopedPolicyChange.MeteredChange)
            if (prev.captivePortal != next.captivePortal) add(ScopedPolicyChange.CaptiveChange)
            if (prev.suspended != next.suspended) add(ScopedPolicyChange.SuspendedChange)
            if (prev.transportKey != next.transportKey) add(ScopedPolicyChange.TransportChange)
        }
    }
}
