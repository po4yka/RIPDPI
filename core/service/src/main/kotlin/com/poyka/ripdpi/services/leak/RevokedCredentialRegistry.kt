package com.poyka.ripdpi.services.leak

/**
 * Tracks revoked credential tokens and answers membership queries.
 *
 * Uses a plain [Set] so the check is O(1) and there are no side effects.
 * Fixture tokens follow the pattern "fixture-token-revoked-N" to satisfy
 * the no-secrets hook.
 */
internal class RevokedCredentialRegistry {
    private val revoked: MutableSet<String> = mutableSetOf()

    /** Marks [token] as revoked. */
    fun revoke(token: String) {
        revoked += token
    }

    /** Returns true when [token] appears in the revoked set. */
    fun isRevoked(token: String): Boolean = token in revoked

    /** Clears all revoked tokens. */
    fun reset() {
        revoked.clear()
    }
}
