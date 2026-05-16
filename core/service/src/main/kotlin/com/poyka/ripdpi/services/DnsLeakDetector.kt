package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DomainClass
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

    /** A DNS leak was detected: a proxy-class domain was resolved via the default network. */
    data class Leaked(
        val domain: String,
        val resolverAddress: String,
    ) : DnsLeakCheckResult
}

/**
 * Detects DNS leaks by inspecting [DnsQueryObservation] records.
 *
 * A leak is defined as: a domain whose [DomainClass] is [DomainClass.PROXY]
 * was resolved via the default network resolver instead of the VPN interceptor.
 *
 * The detector is stateless and side-effect free; callers drive it by recording
 * observations and checking results.
 */
internal class DnsLeakDetector(
    private val policy: SplitStrictDnsPolicy,
) {
    private val classifier: DomainClassifier = AllowlistDomainClassifier(policy)
    private val observations = mutableListOf<DnsQueryObservation>()

    /** Records a new DNS query observation for later inspection. */
    fun record(observation: DnsQueryObservation) {
        observations += observation
    }

    /**
     * Checks [observation] for a leak immediately without storing it.
     *
     * @return [DnsLeakCheckResult.Leaked] when the domain is proxy-class and was
     *         resolved via the default network; [DnsLeakCheckResult.Clean] otherwise.
     */
    fun check(observation: DnsQueryObservation): DnsLeakCheckResult {
        if (!observation.viaDefaultNetwork) return DnsLeakCheckResult.Clean
        val domainClass = classifier.classify(observation.domain)
        return if (domainClass == DomainClass.PROXY) {
            DnsLeakCheckResult.Leaked(
                domain = observation.domain,
                resolverAddress = observation.resolverAddress,
            )
        } else {
            DnsLeakCheckResult.Clean
        }
    }

    /**
     * Returns all leaked observations from [observations].
     *
     * A leaked observation is one where a PROXY-class domain was resolved
     * via the default network.
     */
    fun leakedObservations(): List<DnsQueryObservation> =
        observations.filter { obs ->
            obs.viaDefaultNetwork && classifier.classify(obs.domain) == DomainClass.PROXY
        }

    /** Clears all recorded observations. */
    fun reset() {
        observations.clear()
    }
}
