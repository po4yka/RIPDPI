package com.poyka.ripdpi.services.network

/**
 * A user-visible network *condition* — a description of why a restricted network
 * is misbehaving, distinct from the fail-closed lifecycle machine.
 *
 * This type carries NO authority over how traffic is handled. It is a pure presentation
 * signal: it lets the UI explain "captive portal" or "whitelist-style filtering" instead of
 * surfacing a generic failure. Deciding a [NetworkCondition] MUST NOT change how traffic is
 * carried — see [decideNetworkCondition] and the fail-closed invariant documented in the
 * VpnService outbound-socket rule under `.claude/rules/`.
 */
sealed interface NetworkCondition {
    /** Nothing notable; the network is behaving as a normal validated connection. */
    data object Normal : NetworkCondition

    /**
     * The user has explicitly enabled captive-portal assist on a restricted network.
     *
     * This is a user-VISIBLE, user-INITIATED, auto-EXPIRING condition. It does NOT change how
     * traffic is carried; it merely annotates the connection so the UI can guide the user to
     * the system captive-portal sign-in flow. [expiresAtMillis] bounds the window; once
     * `now >= expiresAtMillis` the assist is considered inactive (see
     * [CaptivePortalAssistActivation.isActiveAt]).
     */
    data class CaptivePortalAssist(
        val activatedAtMillis: Long,
        val expiresAtMillis: Long,
    ) : NetworkCondition

    /**
     * Evidence suggests the network applies whitelist-style filtering: foreign endpoints
     * fail while domestic/allowed endpoints succeed.
     *
     * [suggestRelayProfile] is true only when a configured whitelist relay profile exists
     * locally, so the UI can offer it without implying one is present when it is not.
     */
    data class WhitelistSuspected(
        val suggestRelayProfile: Boolean,
    ) : NetworkCondition

    /** No usable network — the device has no working connectivity. */
    data object NoConnectivity : NetworkCondition

    /**
     * The secure mode is blocked or reconnecting; the network condition is subordinate to that.
     */
    data object BlockedReconnecting : NetworkCondition
}

/**
 * Records an explicit, user-initiated captive-portal assist activation with a finite expiry.
 *
 * Modeled as data (not a side-effecting toggle) so [decideNetworkCondition] stays pure and
 * the expiry behavior is deterministically testable.
 */
data class CaptivePortalAssistActivation(
    val activatedAtMillis: Long,
    val expiresAtMillis: Long,
) {
    /** True only while `now` is within `[activatedAtMillis, expiresAtMillis)`. */
    fun isActiveAt(nowMillis: Long): Boolean = nowMillis in activatedAtMillis until expiresAtMillis
}

/**
 * Deterministic evidence input for the whitelist-suspicion decision.
 *
 * The decision is intentionally evidence-gated: a network is only "whitelist suspected"
 * when normal foreign endpoints fail AND allowed domestic endpoints succeed. If foreign
 * endpoints succeed, or if domestic endpoints also fail, this is NOT whitelist filtering
 * (it is normal connectivity or a total outage respectively).
 *
 * Inputs are deliberately coarse counts — no SSID, BSSID, or device address — to honor the
 * network-fingerprint privacy rule.
 */
data class WhitelistSuspicionEvidence(
    val foreignEndpointsAttempted: Int,
    val foreignEndpointsFailed: Int,
    val domesticEndpointsAttempted: Int,
    val domesticEndpointsSucceeded: Int,
) {
    /** True when every attempted foreign endpoint failed (and at least one was attempted). */
    val allForeignEndpointsFailed: Boolean
        get() = foreignEndpointsAttempted > 0 && foreignEndpointsFailed >= foreignEndpointsAttempted

    /** True when at least one allowed domestic endpoint succeeded. */
    val anyDomesticEndpointSucceeded: Boolean
        get() = domesticEndpointsAttempted > 0 && domesticEndpointsSucceeded > 0
}

/**
 * Pure decision over [WhitelistSuspicionEvidence]. Returns true ONLY for the one-sided
 * pattern that distinguishes whitelist filtering from a normal connection or a full outage:
 * all foreign endpoints failed while a domestic endpoint succeeded.
 */
fun isWhitelistSuspected(evidence: WhitelistSuspicionEvidence): Boolean =
    evidence.allForeignEndpointsFailed && evidence.anyDomesticEndpointSucceeded

/**
 * Pure decision mapping the current network/lifecycle signals to a user-visible
 * [NetworkCondition]. It has NO side effects and holds NO authority over how traffic is
 * handled: callers must never use its result to open broad direct traffic. The result is a
 * presentation signal only.
 *
 * Callers pass plain primitives extracted upstream (from capability/link/lifecycle state),
 * keeping this decision free of any concrete platform input type.
 *
 * Precedence (highest first), with rationale:
 *  1. [NetworkCondition.NoConnectivity] — no usable network means nothing else can be checked
 *     or signed into; report the hard fact first.
 *  2. [NetworkCondition.BlockedReconnecting] — when the fail-closed lifecycle is blocked or
 *     reconnecting, that owns the user's attention; do not mask it with a softer network note.
 *  3. [NetworkCondition.CaptivePortalAssist] — an active, unexpired, user-initiated assist is
 *     the most specific actionable state; surface it above heuristic suspicion.
 *  4. [NetworkCondition.WhitelistSuspected] — evidence-gated heuristic; only when the above
 *     do not apply.
 *  5. [NetworkCondition.Normal] — default.
 *
 * Note: captive-portal *capability* alone (without an active user-initiated assist) does NOT
 * silently change how traffic is carried; it only feeds the UI hint upstream of this decision.
 */
@Suppress("ReturnCount")
fun decideNetworkCondition(
    hasUsableNetwork: Boolean,
    lifecycleBlockedOrReconnecting: Boolean,
    captivePortalAssist: CaptivePortalAssistActivation?,
    whitelistEvidence: WhitelistSuspicionEvidence?,
    hasWhitelistRelayProfile: Boolean,
    nowMillis: Long,
): NetworkCondition {
    if (!hasUsableNetwork) {
        return NetworkCondition.NoConnectivity
    }

    if (lifecycleBlockedOrReconnecting) {
        return NetworkCondition.BlockedReconnecting
    }

    if (captivePortalAssist != null && captivePortalAssist.isActiveAt(nowMillis)) {
        return NetworkCondition.CaptivePortalAssist(
            activatedAtMillis = captivePortalAssist.activatedAtMillis,
            expiresAtMillis = captivePortalAssist.expiresAtMillis,
        )
    }

    if (whitelistEvidence != null && isWhitelistSuspected(whitelistEvidence)) {
        return NetworkCondition.WhitelistSuspected(suggestRelayProfile = hasWhitelistRelayProfile)
    }

    return NetworkCondition.Normal
}
