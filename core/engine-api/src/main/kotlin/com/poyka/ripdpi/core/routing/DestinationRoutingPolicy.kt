package com.poyka.ripdpi.core.routing

import java.util.Collections

/**
 * Engine API destination routing policy.
 *
 * [rules] are evaluated in list order and the first matching rule wins. A destination that does
 * not match any rule uses [defaultAction], which deliberately defaults to the tunneled path.
 * Collection inputs are copied and exposed through unmodifiable views so a compiled snapshot
 * cannot be changed after publication.
 */
class DestinationRoutingPolicy(
    rules: List<DestinationRoutingRule> = emptyList(),
    val defaultAction: DestinationRoutingAction = DestinationRoutingAction.TUNNELED,
    /** Stable digest of the canonical matcher/action representation, excluding source metadata. */
    val canonicalDigest: String,
) {
    val rules: List<DestinationRoutingRule> = rules.immutableCopy()

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is DestinationRoutingPolicy &&
                    rules == other.rules &&
                    defaultAction == other.defaultAction &&
                    canonicalDigest == other.canonicalDigest
            )

    override fun hashCode(): Int {
        var result = rules.hashCode()
        result = HashMultiplier * result + defaultAction.hashCode()
        return HashMultiplier * result + canonicalDigest.hashCode()
    }

    override fun toString(): String =
        "DestinationRoutingPolicy(rules=$rules, defaultAction=$defaultAction, canonicalDigest=$canonicalDigest)"
}

class DestinationRoutingRule(
    val action: DestinationRoutingAction,
    val network: DestinationRoutingNetwork,
    domains: List<DestinationDomainMatcher> = emptyList(),
    ipRanges: List<DestinationIpMatcher> = emptyList(),
    destinationPorts: List<DestinationPortRange> = emptyList(),
) {
    val domains: List<DestinationDomainMatcher> = domains.immutableCopy()
    val ipRanges: List<DestinationIpMatcher> = ipRanges.immutableCopy()
    val destinationPorts: List<DestinationPortRange> = destinationPorts.immutableCopy()

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is DestinationRoutingRule &&
                    action == other.action &&
                    network == other.network &&
                    domains == other.domains &&
                    ipRanges == other.ipRanges &&
                    destinationPorts == other.destinationPorts
            )

    override fun hashCode(): Int {
        var result = action.hashCode()
        result = HashMultiplier * result + network.hashCode()
        result = HashMultiplier * result + domains.hashCode()
        result = HashMultiplier * result + ipRanges.hashCode()
        return HashMultiplier * result + destinationPorts.hashCode()
    }

    override fun toString(): String =
        "DestinationRoutingRule(action=$action, network=$network, domains=$domains, " +
            "ipRanges=$ipRanges, destinationPorts=$destinationPorts)"
}

enum class DestinationRoutingAction {
    TUNNELED,
    DIRECT,
    BLOCK,
}

enum class DestinationRoutingNetwork {
    TCP,
    UDP,
    BOTH,
}

data class DestinationDomainMatcher(
    val kind: DestinationDomainMatcherKind,
    val value: String,
)

enum class DestinationDomainMatcherKind {
    EXACT,
    SUFFIX,
    GEOSITE,
}

data class DestinationIpMatcher(
    val kind: DestinationIpMatcherKind,
    val value: String,
)

enum class DestinationIpMatcherKind {
    CIDR,
    GEO_IP,
}

data class DestinationPortRange(
    val start: Int,
    val endInclusive: Int,
)

private fun <T> List<T>.immutableCopy(): List<T> = Collections.unmodifiableList(ArrayList(this))

private const val HashMultiplier = 31
