package com.poyka.ripdpi.services.redaction

/**
 * Minimal representation of a diagnostics export bundle used for redaction tests.
 *
 * All fields default to empty/null so tests can populate only the fields under test.
 */
data class DiagnosticsBundle(
    val uuid: String? = null,
    val shortId: String? = null,
    val password: String? = null,
    val subscriptionToken: String? = null,
    val endpoint: String? = null,
    val safeLabel: String? = null,
)
