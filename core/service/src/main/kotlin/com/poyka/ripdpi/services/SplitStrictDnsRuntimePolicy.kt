package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.routing.DestinationDomainMatcher
import com.poyka.ripdpi.core.routing.DestinationDomainMatcherKind
import com.poyka.ripdpi.core.routing.DestinationRoutingAction
import com.poyka.ripdpi.core.routing.DestinationRoutingNetwork
import com.poyka.ripdpi.core.routing.DestinationRoutingPolicy
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DnsResolverPlane
import com.poyka.ripdpi.data.NumericIp
import com.poyka.ripdpi.services.routing.DestinationRoutingPolicySnapshot
import java.net.IDN
import java.security.MessageDigest
import java.util.Locale

private const val MaxResolverCandidates = 16
private const val MaxDnsNameLength = 253
private const val MaxDnsLabelLength = 63
private const val MaxRawDnsNameLength = 1024
private const val MaxNumericAddressLength = 64

private const val ByteMask = 0xff

internal data class SplitStrictDnsDecision(
    val plane: DnsResolverPlane,
    val resolverCandidates: List<String> = emptyList(),
    val coverageReason: String? = null,
)

/**
 * Validated, immutable DNS projection of the canonical ordered destination-routing policy.
 *
 * Stage A carries this plan through the service control plane. The v2 TUN serializer deliberately
 * ignores it until the native multi-plane runtime lands in stages B-C.
 */
class ValidatedSplitStrictDnsPolicy private constructor(
    val destinationRouting: DestinationRoutingPolicy,
    directResolverCandidates: List<String>,
    bootstrapPins: List<String>,
    val policyCoverageReason: String?,
    val underlayLeaseGeneration: Long? = null,
) {
    val directResolverCandidates: List<String> = directResolverCandidates.toList()
    val bootstrapPins: List<String> = bootstrapPins.toList()
    val canonicalDigest: String =
        if (destinationRouting.rules.isEmpty() && policyCoverageReason == null) {
            ""
        } else {
            listOf(
                destinationRouting.canonicalDigest,
                this.directResolverCandidates.joinToString(","),
                this.bootstrapPins.joinToString(","),
                policyCoverageReason.orEmpty(),
            ).joinToString("|").encodeSha256()
        }

    internal fun decide(queryName: String): SplitStrictDnsDecision =
        canonicalDnsName(queryName)?.let(::projectCanonicalName) ?: proxyDecision("invalid_qname")

    private fun projectCanonicalName(queryName: String): SplitStrictDnsDecision =
        destinationRouting.rules
            .asSequence()
            .mapNotNull { rule ->
                val match = evaluateDomainMatchers(rule.domains, queryName)
                when (match) {
                    DomainMatch.NO_MATCH -> null
                    DomainMatch.INCONCLUSIVE -> proxyDecision("unsupported_domain_matcher")
                    DomainMatch.MATCH -> projectMatchedRule(rule)
                }
            }.firstOrNull()
            ?: proxyDecision(policyCoverageReason)

    private fun projectMatchedRule(rule: com.poyka.ripdpi.core.routing.DestinationRoutingRule): SplitStrictDnsDecision =
        if (
            rule.network != DestinationRoutingNetwork.BOTH ||
            rule.ipRanges.isNotEmpty() ||
            rule.destinationPorts.isNotEmpty()
        ) {
            proxyDecision("mixed_route_constraints")
        } else {
            when (rule.action) {
                DestinationRoutingAction.TUNNELED -> proxyDecision()
                DestinationRoutingAction.BLOCK -> SplitStrictDnsDecision(DnsResolverPlane.BLOCK)
                DestinationRoutingAction.DIRECT -> directDecision()
            }
        }

    private fun directDecision(): SplitStrictDnsDecision =
        if (directResolverCandidates.isEmpty()) {
            proxyDecision("direct_resolver_unavailable")
        } else {
            SplitStrictDnsDecision(
                plane = DnsResolverPlane.DIRECT,
                resolverCandidates = directResolverCandidates,
            )
        }

    private fun proxyDecision(reason: String? = null): SplitStrictDnsDecision =
        SplitStrictDnsDecision(
            plane = DnsResolverPlane.PROXY,
            coverageReason = reason,
        )

    companion object {
        fun build(
            activeDns: ActiveDnsSettings,
            routingSnapshot: DestinationRoutingPolicySnapshot,
            underlayDnsServers: List<String>,
            underlayLeaseGeneration: Long? = null,
        ): ValidatedSplitStrictDnsPolicy {
            require(activeDns.isEncrypted) { "Split-strict proxy resolver must be encrypted" }
            val bootstrap = numericAddressesOrEmpty(activeDns.encryptedDnsBootstrapIps, "bootstrap")
            val policy =
                when (routingSnapshot) {
                    is DestinationRoutingPolicySnapshot.Available -> routingSnapshot.policy
                    is DestinationRoutingPolicySnapshot.Unavailable -> DestinationRoutingPolicy(canonicalDigest = "")
                }
            require(policy.defaultAction == DestinationRoutingAction.TUNNELED) {
                "Split-strict DNS requires a tunneled default route"
            }
            val usesDirectDns = policy.hasUsableDirectDnsRule()
            val underlay =
                if (usesDirectDns) numericAddressesOrEmpty(underlayDnsServers) else NumericAddresses(emptyList())
            return ValidatedSplitStrictDnsPolicy(
                destinationRouting = policy,
                directResolverCandidates = underlay.values,
                bootstrapPins = bootstrap.values,
                policyCoverageReason =
                    when (routingSnapshot) {
                        is DestinationRoutingPolicySnapshot.Unavailable -> {
                            "route_policy_unavailable:${routingSnapshot.reason}"
                        }

                        is DestinationRoutingPolicySnapshot.Available -> {
                            if (routingSnapshot.policy.rules.isEmpty()) {
                                null
                            } else {
                                bootstrap.reason ?: underlay.reason
                            }
                        }
                    },
                underlayLeaseGeneration = underlayLeaseGeneration.takeIf { usesDirectDns },
            )
        }
    }
}

private fun DestinationRoutingPolicy.hasUsableDirectDnsRule(): Boolean =
    rules.any { rule ->
        rule.action == DestinationRoutingAction.DIRECT &&
            rule.network == DestinationRoutingNetwork.BOTH &&
            rule.ipRanges.isEmpty() &&
            rule.destinationPorts.isEmpty()
    }

private data class NumericAddresses(
    val values: List<String>,
    val reason: String? = null,
)

private const val DirectResolverRole = "direct"

private fun isLinkLocalResolverLiteral(raw: String): Boolean =
    NumericIp.parseAddress(raw.substringBefore('%'))?.let { it.isIPv6 && it.toInetAddress().isLinkLocalAddress } == true

private fun numericAddressesOrEmpty(
    values: List<String>,
    role: String = DirectResolverRole,
): NumericAddresses =
    if (values.size > MaxResolverCandidates) {
        NumericAddresses(emptyList(), "${role}_resolver_list_too_large")
    } else {
        // Link-local resolvers need an interface scope a policy literal cannot carry; skip them so the
        // global resolvers stay usable. The underlay lease drops the same resolvers.
        val usable = if (role == DirectResolverRole) values.filterNot(::isLinkLocalResolverLiteral) else values
        val parsed = usable.mapNotNull(::canonicalNumericAddress)
        if (parsed.size != usable.size) {
            NumericAddresses(emptyList(), "${role}_resolver_invalid")
        } else {
            NumericAddresses(parsed.distinct())
        }
    }

private fun canonicalNumericAddress(raw: String): String? =
    raw.takeIf { it.length <= MaxNumericAddressLength }?.let(NumericIp::parseAddress)?.toNormalizedString()

private enum class DomainMatch { MATCH, NO_MATCH, INCONCLUSIVE }

private fun evaluateDomainMatchers(
    matchers: List<DestinationDomainMatcher>,
    queryName: String,
): DomainMatch =
    if (matchers.isEmpty()) {
        DomainMatch.MATCH
    } else {
        val results = matchers.map { matcher -> matcher.evaluate(queryName) }
        when {
            DomainMatch.MATCH in results -> DomainMatch.MATCH
            DomainMatch.INCONCLUSIVE in results -> DomainMatch.INCONCLUSIVE
            else -> DomainMatch.NO_MATCH
        }
    }

private fun DestinationDomainMatcher.evaluate(queryName: String): DomainMatch =
    when (kind) {
        DestinationDomainMatcherKind.EXACT -> {
            if (queryName == value) DomainMatch.MATCH else DomainMatch.NO_MATCH
        }

        DestinationDomainMatcherKind.SUFFIX -> {
            if (queryName == value || queryName.endsWith(".$value")) DomainMatch.MATCH else DomainMatch.NO_MATCH
        }

        DestinationDomainMatcherKind.GEOSITE -> {
            DomainMatch.INCONCLUSIVE
        }
    }

internal fun canonicalDnsName(raw: String): String? =
    raw
        .takeIf(::isBoundedDnsName)
        ?.removeSuffix(".")
        ?.let { value -> runCatching { IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES) }.getOrNull() }
        ?.lowercase(Locale.ROOT)
        ?.takeIf(::isValidAsciiDnsName)

private fun isBoundedDnsName(raw: String): Boolean {
    val bounded = raw.isNotEmpty() && raw.length <= MaxRawDnsNameLength
    val canonicalSpacing = raw == raw.trim()
    return bounded && canonicalSpacing && !raw.endsWith("..")
}

private fun isValidAsciiDnsName(value: String): Boolean =
    value.isNotEmpty() &&
        value.length <= MaxDnsNameLength &&
        value.split('.').none { label -> label.isEmpty() || label.length > MaxDnsLabelLength }

private fun String.encodeSha256(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return buildString(bytes.size * 2) {
        bytes.forEach { byte -> append("%02x".format(Locale.ROOT, byte.toInt() and ByteMask)) }
    }
}
