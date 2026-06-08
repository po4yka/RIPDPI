package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DnsResolverPlane
import com.poyka.ripdpi.data.SplitStrictDnsPolicy

/**
 * Represents a DNS query observation recorded by the leak detector.
 *
 * @param domain          The queried domain name.
 * @param resolverAddress The actual resolver IP that handled the query.
 * @param viaDefaultNetwork Whether the query was routed via the default (non-VPN) network.
 */
internal data class DnsQueryObservation(
    val domain: String,
    val resolverAddress: String,
    val viaDefaultNetwork: Boolean,
)

/**
 * Outcome of a leak check.
 */
internal sealed interface DnsLeakCheckResult {
    /** No leak detected; the query was routed through the expected plane. */
    data object Clean : DnsLeakCheckResult

    /** A DNS leak was detected: a non-direct domain was resolved via the default network. */
    data class Leaked(
        val domain: String,
        val resolverAddress: String,
    ) : DnsLeakCheckResult
}

/**
 * Detects DNS leaks by inspecting [DnsQueryObservation] records.
 *
 * A leak is defined as: a domain whose interceptor dispatch result is not
 * [DnsResolverPlane.DIRECT] was resolved via the default network resolver
 * instead of the VPN interceptor.
 *
 * The detector is stateless and side-effect free; callers drive it by recording
 * observations and checking results.
 */
internal class DnsLeakDetector(
    private val policy: SplitStrictDnsPolicy,
    private val privacyThreatStateStore: PrivacyThreatStateStore? = null,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val dispatcher = DnsInterceptorDispatcher(policy)
    private val observations = mutableListOf<DnsQueryObservation>()

    /** Records a new DNS query observation for later inspection. */
    fun record(observation: DnsQueryObservation) {
        observations += observation
    }

    /**
     * Checks [observation] for a leak immediately without storing it.
     *
     * @return [DnsLeakCheckResult.Leaked] when the domain is not dispatched DIRECT and was
     *         resolved via the default network; [DnsLeakCheckResult.Clean] otherwise.
     */
    fun check(observation: DnsQueryObservation): DnsLeakCheckResult {
        if (!observation.viaDefaultNetwork) return publish(DnsLeakCheckResult.Clean)
        val dispatch = dispatcher.dispatch(observation.domain)
        return if (dispatch.plane == DnsResolverPlane.DIRECT) {
            publish(DnsLeakCheckResult.Clean)
        } else {
            publish(
                DnsLeakCheckResult.Leaked(
                    domain = observation.domain,
                    resolverAddress = observation.resolverAddress,
                ),
            )
        }
    }

    private fun publish(result: DnsLeakCheckResult): DnsLeakCheckResult {
        privacyThreatStateStore?.updateDnsLeak(
            PrivacyThreatLeakResultMapper.dns(
                result = result,
                nowMillis = clockMillis(),
            ),
        )
        return result
    }

    /**
     * Returns all leaked observations from [observations].
     *
     * A leaked observation is one where a non-DIRECT domain was resolved via
     * the default network.
     */
    fun leakedObservations(): List<DnsQueryObservation> =
        observations.filter { obs ->
            check(obs) is DnsLeakCheckResult.Leaked
        }

    /** Clears all recorded observations. */
    fun reset() {
        observations.clear()
    }
}
