package com.poyka.ripdpi.data.selector

/**
 * Broad transport kind used for auto-selector ordering.
 *
 * Ordering priority in [AutoSelector]: [RealityDirect] > [NonCloudflareHttps] > [CloudflareXhttp].
 */
enum class RelayProfileKind {
    RealityDirect,
    NonCloudflareHttps,
    CloudflareXhttp,
}

/**
 * A profile entry visible to the selector.
 *
 * @param id Unique profile identifier.
 * @param kind Transport kind used to determine ordering priority.
 * @param isCloudflareBacked True when this profile routes traffic through Cloudflare.
 * @param label UI label; Cloudflare-backed profiles carry [ProfileSelectorLabel.OptionalEdgeFallback].
 */
data class ProfileCandidate(
    val id: String,
    val kind: RelayProfileKind,
    val isCloudflareBacked: Boolean,
    val label: ProfileSelectorLabel,
)
