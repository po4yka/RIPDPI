package com.poyka.ripdpi.data.selector

/**
 * Result of a manual profile selection.
 *
 * @param selectedId The id of the profile the user explicitly chose.
 */
data class ManualSelectionResult(
    val selectedId: String,
)

/**
 * Performs an unconditional manual profile selection.
 *
 * Unlike [AutoSelector], this path does not consult [CloudflareHealthGate].
 * A user who explicitly picks a Cloudflare-backed profile while health is
 * degraded has made an informed choice and must not be blocked.
 */
object ManualSelector {
    fun select(profile: ProfileCandidate): ManualSelectionResult = ManualSelectionResult(selectedId = profile.id)
}
