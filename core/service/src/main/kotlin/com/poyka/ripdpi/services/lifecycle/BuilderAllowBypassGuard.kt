package com.poyka.ripdpi.services.lifecycle

/**
 * Guards calls to [android.net.VpnService.Builder.allowBypass].
 *
 * Secure profiles MUST NOT call allowBypass unless the user has explicitly
 * enabled the unsafe-bypass setting on that profile.
 */
class BuilderAllowBypassGuard {
    /**
     * Returns true only when [profile] has [VpnProfile.unsafeAllowBypass] set to true.
     *
     * Defaults to false — fail closed.
     */
    fun allow(profile: VpnProfile): Boolean = profile.unsafeAllowBypass
}

/**
 * Minimal profile descriptor used by [BuilderAllowBypassGuard].
 *
 * In production, derive from the real settings model; in tests, construct directly.
 */
data class VpnProfile(
    /** When true, the user has explicitly opted in to allowing bypass. Defaults to false. */
    val unsafeAllowBypass: Boolean = false,
)
