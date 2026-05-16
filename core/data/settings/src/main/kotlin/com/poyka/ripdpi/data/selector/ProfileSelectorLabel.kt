package com.poyka.ripdpi.data.selector

/**
 * UI label for a profile candidate in the selector list.
 *
 * [Primary] — direct REALITY or non-Cloudflare HTTPS paths that are preferred in auto mode.
 * [OptionalEdgeFallback] — Cloudflare-backed paths that are deprioritised and excluded
 * from auto selection when Cloudflare health is degraded.
 */
enum class ProfileSelectorLabel {
    Primary,
    OptionalEdgeFallback,
}
