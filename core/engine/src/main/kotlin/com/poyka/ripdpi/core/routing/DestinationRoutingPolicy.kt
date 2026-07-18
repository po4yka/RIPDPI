package com.poyka.ripdpi.core.routing

/**
 * Engine-owned, wire-neutral destination routing policy.
 *
 * [rules] are evaluated in list order and the first matching rule wins. A destination that does
 * not match any rule uses [defaultAction], which deliberately defaults to the tunneled path.
 */
data class DestinationRoutingPolicy(
    val rules: List<DestinationRoutingRule> = emptyList(),
    val defaultAction: DestinationRoutingAction = DestinationRoutingAction.TUNNELED,
    /** Stable digest of the canonical matcher/action representation, excluding source metadata. */
    val canonicalDigest: String,
)

data class DestinationRoutingRule(
    val action: DestinationRoutingAction,
    val network: DestinationRoutingNetwork,
    val domains: List<DestinationDomainMatcher> = emptyList(),
    val ipRanges: List<DestinationIpMatcher> = emptyList(),
    val destinationPorts: List<DestinationPortRange> = emptyList(),
)

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
