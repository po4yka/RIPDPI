package com.poyka.ripdpi.data

/**
 * Per-app-family `NO_TCP_FALLBACK` memory — the live-owner Kotlin mirror of the
 * native reference type `ripdpi_runtime_policy::app_family_memory::AppFamilyMemory`.
 *
 * When an app never retries over TCP after a UDP/QUIC suppression event (the
 * `NO_TCP_FALLBACK` heuristic fires for its traffic), the app's package identity
 * is recorded here so the direct-mode policy engine does not re-apply
 * `QuicMode.SOFT_DISABLE` to the same app again — soft-disabling QUIC for an app
 * that hard-depends on it breaks the app.
 *
 * The mark is keyed by `(packageName, versionCode)`. The version code acts as a
 * cache-buster: a mark recorded for version N is invisible when queried with
 * version M ≠ N, so an app update **implicitly reverts** the memory (the
 * "reverts on app package version change" requirement). [invalidateOnAppUpdate]
 * additionally provides eager removal when a package-replaced broadcast arrives.
 *
 * Conservative by default: an *unattributed* signal — one with no package
 * identity — is never eligible to set or be matched by this memory, so the
 * direct-mode engine falls back to authority/IP-scoped policy and never pins a
 * per-app verdict it cannot attribute. Per-flow UID→package attribution is wired
 * through `FlowAttributionBridge` and `FlowAppAttributionStore` into the
 * app-identity-aware learner. Signals whose owning package cannot be resolved
 * therefore remain ineligible for this memory.
 *
 * Pure in-memory and not thread-safe by itself; callers hold it under their own
 * session scope (mirroring the reference type, which is also a plain in-memory
 * store).
 */
class NoTcpFallbackAppMemory {
    /** Maps `packageName` → the version code the NO_TCP_FALLBACK mark was recorded for. */
    private val marked = linkedMapOf<String, Long>()

    /**
     * Record that [packageName] at [versionCode] never retried over TCP after a
     * UDP/QUIC suppression event. A blank package name is ignored (conservative:
     * unattributed signals never set a per-app mark). An earlier mark for the
     * same package at a different version is silently replaced.
     */
    fun recordNoTcpFallback(
        packageName: String,
        versionCode: Long,
    ) {
        val normalized = packageName.trim()
        if (normalized.isEmpty()) return
        marked[normalized] = versionCode
    }

    /**
     * Returns `true` only when [packageName] has been marked NO_TCP_FALLBACK for
     * the **exact** [versionCode]. Returns `false` for blank/unknown packages or
     * when the version differs from the recorded one (i.e. the app was updated).
     */
    fun shouldSkipSoftDisable(
        packageName: String,
        versionCode: Long,
    ): Boolean {
        val normalized = packageName.trim()
        if (normalized.isEmpty()) return false
        return marked[normalized] == versionCode
    }

    /**
     * Eagerly remove the NO_TCP_FALLBACK mark for [packageName] when it has been
     * updated to [newVersionCode]. After this call [shouldSkipSoftDisable]
     * returns `false` for [packageName] until a new mark is recorded for
     * [newVersionCode]. No-op when no mark exists or the recorded version already
     * matches [newVersionCode].
     */
    fun invalidateOnAppUpdate(
        packageName: String,
        newVersionCode: Long,
    ) {
        val normalized = packageName.trim()
        val recorded = marked[normalized] ?: return
        if (recorded != newVersionCode) {
            marked.remove(normalized)
        }
    }

    /** Drop every mark (e.g. on a full policy-cache clear). */
    fun clear() {
        marked.clear()
    }
}
