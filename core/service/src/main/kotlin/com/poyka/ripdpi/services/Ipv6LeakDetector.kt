package com.poyka.ripdpi.services

/**
 * Represents an observed IPv6 traffic event used for leak detection.
 *
 * @param sourceAddress      The source IPv6 address of the observed packet or connection.
 * @param viaDefaultNetwork  Whether the traffic was observed on the default (non-VPN) network.
 * @param vpnActive          Whether the VPN tunnel was active at the time of observation.
 */
internal data class Ipv6TrafficObservation(
    val sourceAddress: String,
    val viaDefaultNetwork: Boolean,
    val vpnActive: Boolean,
)

/**
 * Outcome of an IPv6 leak check.
 */
internal sealed interface Ipv6LeakCheckResult {
    /** No leak: the traffic was routed correctly or VPN was not active. */
    data object Clean : Ipv6LeakCheckResult

    /**
     * A leak was detected: a public IPv6 address was observed on the default
     * (non-VPN) network while the VPN tunnel was active.
     */
    data class Leaked(
        val sourceAddress: String,
    ) : Ipv6LeakCheckResult
}

/**
 * Detects IPv6 leaks by inspecting [Ipv6TrafficObservation] records.
 *
 * A leak is defined as: traffic with a public (non-link-local, non-ULA) source
 * IPv6 address was observed on the default network while the VPN tunnel was active.
 *
 * Link-local (fe80::/10) and ULA (fc00::/7) addresses are private and never
 * considered leaks regardless of the network path.
 */
internal class Ipv6LeakDetector {
    private val observations = mutableListOf<Ipv6TrafficObservation>()

    /** Records a new traffic observation for later inspection. */
    fun record(observation: Ipv6TrafficObservation) {
        observations += observation
    }

    /**
     * Checks [observation] for a leak immediately without storing it.
     *
     * @return [Ipv6LeakCheckResult.Leaked] when a public IPv6 source address was
     *         observed on the default network while the VPN was active;
     *         [Ipv6LeakCheckResult.Clean] otherwise.
     */
    fun check(observation: Ipv6TrafficObservation): Ipv6LeakCheckResult =
        if (isLeaked(observation)) {
            Ipv6LeakCheckResult.Leaked(sourceAddress = observation.sourceAddress)
        } else {
            Ipv6LeakCheckResult.Clean
        }

    private fun isLeaked(observation: Ipv6TrafficObservation): Boolean =
        observation.vpnActive &&
            observation.viaDefaultNetwork &&
            !isPrivateIpv6(observation.sourceAddress)

    /**
     * Returns all leaked observations from the recorded history.
     */
    fun leakedObservations(): List<Ipv6TrafficObservation> = observations.filter { obs -> isLeaked(obs) }

    /** Returns `true` if at least one leaked observation has been recorded. */
    fun hasLeak(): Boolean = leakedObservations().isNotEmpty()

    /** Clears all recorded observations. */
    fun reset() {
        observations.clear()
    }

    companion object {
        /**
         * Returns `true` for link-local (fe80::/10) and ULA (fc00::/7) addresses
         * which are private and never indicative of a leak.
         */
        fun isPrivateIpv6(address: String): Boolean {
            val lower = address.lowercase()
            return lower.startsWith("fe80") ||
                lower.startsWith("fc") ||
                lower.startsWith("fd")
        }
    }
}
