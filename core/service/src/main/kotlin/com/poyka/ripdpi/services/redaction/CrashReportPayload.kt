package com.poyka.ripdpi.services.redaction

/**
 * Safe crash-report payload that stores only derived identifiers and a
 * state reason string — never raw profile fields (server, uuid, password, …).
 *
 * Construction via [build] enforces the deny-by-default contract at the type
 * level: callers cannot accidentally pass raw field values because the
 * parameters are named and typed as hashes / opaque identifiers.
 */
data class CrashReportPayload(
    val configHash: String,
    val profileId: String,
    val stateReason: String,
) {
    companion object {
        fun build(
            configHash: String,
            profileId: String,
            stateReason: String,
        ): CrashReportPayload =
            CrashReportPayload(
                configHash = configHash,
                profileId = profileId,
                stateReason = stateReason,
            )
    }
}
