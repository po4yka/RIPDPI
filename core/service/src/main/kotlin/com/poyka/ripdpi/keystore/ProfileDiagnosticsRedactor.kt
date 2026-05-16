package com.poyka.ripdpi.keystore

import com.poyka.ripdpi.services.redaction.DiagnosticsBundle
import com.poyka.ripdpi.services.redaction.DiagnosticsRedactor

/**
 * Produces a safe [DiagnosticsBundle] from raw profile credential fields.
 *
 * Callers supply raw credential values; this class hashes/fingerprints them
 * and delegates final redaction to [DiagnosticsRedactor] so the exported
 * bundle never contains raw secrets.
 *
 * The [configHash] is a caller-supplied opaque fingerprint of the profile
 * configuration (e.g. SHA-256 of the serialized profile without secrets).
 * It is preserved in the bundle because it is safe to share.
 */
class ProfileDiagnosticsRedactor {
    /**
     * Builds a [DiagnosticsBundle] from the given raw profile fields and
     * immediately redacts all sensitive entries via [DiagnosticsRedactor].
     *
     * [configHash] is kept verbatim because it is a derived, non-reversible
     * identifier. All other parameters are treated as sensitive and redacted.
     */
    fun redactedBundle(
        configHash: String,
        uuid: String? = null,
        shortId: String? = null,
        password: String? = null,
        subscriptionToken: String? = null,
        endpoint: String? = null,
    ): DiagnosticsBundle {
        val raw =
            DiagnosticsBundle(
                uuid = uuid,
                shortId = shortId,
                password = password,
                subscriptionToken = subscriptionToken,
                endpoint = endpoint,
                safeLabel = configHash,
            )
        return DiagnosticsRedactor.redact(raw)
    }
}
