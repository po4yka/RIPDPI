package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DnsResolverPlane
import com.poyka.ripdpi.data.DomainClass
import com.poyka.ripdpi.data.SplitStrictDnsPolicy

/**
 * Result of routing a DNS query through the interceptor.
 *
 * @param plane     The resolver plane the query is dispatched to.
 * @param endpoint  The upstream resolver endpoint for the plane, or empty for BLOCK.
 */
internal data class DnsDispatchResult(
    val plane: DnsResolverPlane,
    val endpoint: String,
)

/**
 * Classifies an incoming domain name into a [DomainClass].
 *
 * Implementations check whether a domain matches a known direct-route
 * allowlist, block list, or falls through to the proxy default.
 */
internal fun interface DomainClassifier {
    fun classify(domain: String): DomainClass
}

/**
 * Allowlist-backed [DomainClassifier] that routes domains in
 * [SplitStrictDnsPolicy.directAllowlist] to [DomainClass.DIRECT] and
 * everything else to [DomainClass.PROXY].
 */
internal class AllowlistDomainClassifier(
    private val policy: SplitStrictDnsPolicy,
) : DomainClassifier {
    override fun classify(domain: String): DomainClass {
        val normalised = domain.trimEnd('.')
        val matchesDirect =
            policy.directAllowlist.any { suffix ->
                normalised == suffix || normalised.endsWith(".$suffix")
            }
        return if (matchesDirect) DomainClass.DIRECT else DomainClass.PROXY
    }
}

/**
 * Routes an incoming DNS query to the correct resolver plane according to
 * [SplitStrictDnsPolicy] and a [DomainClassifier].
 *
 * Rules:
 * - [DomainClass.BLOCK] → BLOCK plane; no upstream is contacted.
 * - [DomainClass.DIRECT] → DIRECT plane if configured; falls back to PROXY.
 * - [DomainClass.PROXY] → PROXY plane (strict-encrypted, no plaintext fallback).
 */
internal class DnsInterceptorDispatcher(
    private val policy: SplitStrictDnsPolicy,
    private val classifier: DomainClassifier = AllowlistDomainClassifier(policy),
) {
    fun dispatch(domain: String): DnsDispatchResult {
        val domainClass = classifier.classify(domain)
        return when (domainClass) {
            DomainClass.BLOCK -> {
                DnsDispatchResult(
                    plane = DnsResolverPlane.BLOCK,
                    endpoint = "",
                )
            }

            DomainClass.DIRECT -> {
                val directSpec = policy.direct
                if (directSpec != null) {
                    DnsDispatchResult(
                        plane = DnsResolverPlane.DIRECT,
                        endpoint = directSpec.endpoint,
                    )
                } else {
                    DnsDispatchResult(
                        plane = DnsResolverPlane.PROXY,
                        endpoint = policy.proxy.endpoint,
                    )
                }
            }

            DomainClass.PROXY -> {
                DnsDispatchResult(
                    plane = DnsResolverPlane.PROXY,
                    endpoint = policy.proxy.endpoint,
                )
            }
        }
    }
}
