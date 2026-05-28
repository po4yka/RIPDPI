package com.poyka.ripdpi.diagnostics.shared

/**
 * Minimal neutral transport-policy descriptor shared by both mode arms.
 *
 * - [requiresEch] true  → the target needs ECH, therefore owned-stack is required.
 * - [requiresTunProtect] true → traffic flows through the TUN interface (transparent mode).
 *
 * [resolveMode] encodes the mode-selection rule: if ECH is required, the
 * owned-stack path is mandated regardless of other flags; otherwise the
 * transparent path is used.  This is the canonical selection rule described
 * in docs/architecture/transparent-vs-owned-stack-boundary.md.
 *
 * Intentionally carries no platform HTTP or VpnService references.
 */
data class TransportPolicyStub(
    val requiresEch: Boolean,
    val requiresTunProtect: Boolean,
) {
    /**
     * Returns the [TransportMode] dictated by this policy.
     *
     * The when-expression is exhaustive over [TransportMode] variants,
     * enforcing the type-level invariant that owned-stack paths cannot
     * accidentally resolve to [TransportMode.Transparent].
     */
    fun resolveMode(): TransportMode =
        when {
            requiresEch -> TransportMode.OwnedStack
            else -> TransportMode.Transparent
        }
}
