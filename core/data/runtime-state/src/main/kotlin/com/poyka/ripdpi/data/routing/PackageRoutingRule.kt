package com.poyka.ripdpi.data.routing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Per-app routing action for a single Android package.
 *
 * Ported from the action set the ripdpi-vpn-deploy `emit-singbox.sh` deployer
 * encodes in `route.rules[]`:
 * - [BYPASS] — `outbound: "direct"`: the package skips the tunnel entirely
 *   (`VpnService.Builder.addDisallowedApplication`).
 * - [VIA_TUN] — `outbound: "select"`: the package is forced through the
 *   selector group (`addAllowedApplication`).
 * - [VIA_OUTBOUND] — any other named outbound: the package is routed through a
 *   specific named outbound rather than the default selector.
 */
@Serializable
enum class PackageRoutingAction {
    BYPASS,
    VIA_TUN,
    VIA_OUTBOUND,
}

/**
 * Provenance of a [PackageRoutingRule]. A rule is either [User]-authored (set
 * by hand on the per-app routing screen) or imported from a subscription bundle
 * and tagged with that subscription's id so it can be removed as a unit when
 * the subscription is deleted.
 */
@Serializable
sealed interface PackageRoutingRuleOrigin {
    /** The rule was set by the user directly; it survives subscription refresh and removal. */
    @Serializable
    @SerialName("user")
    data object User : PackageRoutingRuleOrigin

    /**
     * The rule was imported from the subscription identified by
     * [subscriptionId]. Removing that subscription removes exactly these rules.
     */
    @Serializable
    @SerialName("subscription")
    data class Subscription(
        val subscriptionId: String,
    ) : PackageRoutingRuleOrigin
}

/**
 * A single per-app routing rule: [action] applied to the app identified by
 * [packageName], tagged with its [origin].
 */
@Serializable
data class PackageRoutingRule(
    val packageName: String,
    val action: PackageRoutingAction,
    val origin: PackageRoutingRuleOrigin,
)
