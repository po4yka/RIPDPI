package com.poyka.ripdpi.services.dns.bootstrap

/** Result of checking a name against [BootstrapAllowlist]. */
sealed class AllowlistResult {
    /** The name is in the allowlist and may be resolved. */
    data object Permitted : AllowlistResult()

    /** The name is not in the allowlist and must be rejected. */
    data object Denied : AllowlistResult()
}

/**
 * Guards bootstrap DNS resolution to a fixed set of hostnames.
 *
 * Only names explicitly listed in [names] are permitted. Any other name
 * produces [AllowlistResult.Denied] and must not be forwarded to any resolver.
 *
 * @param names The exact hostnames permitted during bootstrap.
 */
class BootstrapAllowlist(
    private val names: Set<String>,
) {
    /**
     * Check whether [name] is in the allowlist.
     *
     * @return [AllowlistResult.Permitted] when [name] is in the allowlist,
     *         [AllowlistResult.Denied] otherwise.
     */
    fun check(name: String): AllowlistResult =
        if (name.isNotEmpty() && names.contains(name)) {
            AllowlistResult.Permitted
        } else {
            AllowlistResult.Denied
        }
}
