package com.poyka.ripdpi.data.selector

/**
 * Decides whether a [ProfileCandidate] is eligible for automatic selection
 * given the current [CloudflareHealthState].
 *
 * Non-Cloudflare profiles are always eligible. Cloudflare-backed profiles are
 * eligible only when health is [CloudflareHealthState.Healthy].
 */
object CloudflareHealthGate {
    fun shouldIncludeInAuto(
        profile: ProfileCandidate,
        healthState: CloudflareHealthState,
    ): Boolean {
        if (!profile.isCloudflareBacked) return true
        return healthState == CloudflareHealthState.Healthy
    }
}
