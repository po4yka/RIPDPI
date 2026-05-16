package com.poyka.ripdpi.data.selector

/**
 * Orders [ProfileCandidate] entries for automatic profile selection.
 *
 * Ordering:
 * 1. [RelayProfileKind.RealityDirect] — preferred, lowest latency, no Cloudflare risk.
 * 2. [RelayProfileKind.NonCloudflareHttps] — reliable TLS fallback.
 * 3. [RelayProfileKind.CloudflareXhttp] — optional edge fallback, excluded when
 *    [CloudflareHealthState] is [CloudflareHealthState.Degraded].
 *
 * Profiles that fail the [CloudflareHealthGate] check are dropped entirely from
 * the returned list.
 */
object AutoSelector {
    fun order(
        candidates: List<ProfileCandidate>,
        healthState: CloudflareHealthState,
    ): List<ProfileCandidate> =
        candidates
            .filter { CloudflareHealthGate.shouldIncludeInAuto(it, healthState) }
            .sortedBy { it.kind.ordinal }
}
