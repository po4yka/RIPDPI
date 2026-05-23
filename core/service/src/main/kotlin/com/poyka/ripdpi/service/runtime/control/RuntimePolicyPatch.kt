package com.poyka.ripdpi.service.runtime.control

/**
 * Payload for [RuntimeControlCommand.ApplyPolicyPatch] — an opaque, caller-built
 * description of a connection-policy change to apply to the live runtime.
 *
 * It is intentionally minimal: the control plane does not interpret the patch,
 * it only routes it to the actions layer. The connection-policy resolver and
 * the per-network policy memory remain the authority on policy *content*; this
 * type is just the envelope a future policy-memory feature fills in when it
 * adopts the control-plane seam.
 */
internal data class RuntimePolicyPatch(
    /** Human-readable description of what produced the patch (e.g. a settings key). */
    val summary: String,
)
