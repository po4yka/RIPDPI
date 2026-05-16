package com.poyka.ripdpi.services.leak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Revoked credential matrix: stale UUID/shortId/password/profile tokens must
 * be rejected by local validation paths.
 *
 * All fixture tokens follow the "fixture-*-revoked-*" pattern to avoid
 * triggering the no-secrets hook.
 */
class RevokedCredentialMatrixTest {
    private val registry = RevokedCredentialRegistry()

    @Test
    fun revokedUuidTokenIsRejected() {
        registry.revoke("fixture-uuid-revoked-1")
        assertTrue(registry.isRevoked("fixture-uuid-revoked-1"))
    }

    @Test
    fun revokedShortIdTokenIsRejected() {
        registry.revoke("fixture-shortid-revoked-1")
        assertTrue(registry.isRevoked("fixture-shortid-revoked-1"))
    }

    @Test
    fun revokedPasswordTokenIsRejected() {
        registry.revoke("fixture-password-revoked-1")
        assertTrue(registry.isRevoked("fixture-password-revoked-1"))
    }

    @Test
    fun revokedProfileTokenIsRejected() {
        registry.revoke("fixture-profile-revoked-1")
        assertTrue(registry.isRevoked("fixture-profile-revoked-1"))
    }

    @Test
    fun activeTokenIsNotRejected() {
        registry.revoke("fixture-uuid-revoked-1")
        assertFalse(registry.isRevoked("fixture-uuid-active-1"))
    }

    @Test
    fun multipleRevokedTokensAllRejected() {
        val tokens =
            listOf(
                "fixture-uuid-revoked-2",
                "fixture-shortid-revoked-2",
                "fixture-password-revoked-2",
                "fixture-profile-revoked-2",
            )
        tokens.forEach { registry.revoke(it) }
        tokens.forEach { token ->
            assertTrue("Expected $token to be revoked", registry.isRevoked(token))
        }
    }

    @Test
    fun unknownTokenIsNotRevoked() {
        assertFalse(registry.isRevoked("fixture-uuid-unknown-1"))
    }

    @Test
    fun resetClearsAllRevokedTokens() {
        registry.revoke("fixture-uuid-revoked-3")
        registry.reset()
        assertFalse(registry.isRevoked("fixture-uuid-revoked-3"))
    }

    @Test
    fun revokedTokenDoesNotAffectDifferentToken() {
        registry.revoke("fixture-uuid-revoked-4")
        assertFalse(registry.isRevoked("fixture-shortid-active-4"))
    }
}
