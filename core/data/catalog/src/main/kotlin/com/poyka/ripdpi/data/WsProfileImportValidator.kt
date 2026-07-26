package com.poyka.ripdpi.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Import-time validator for WS-tunnel profiles.
 *
 * The native ws-tunnel runtime refuses `fake_sni` at connect time
 * when `allow_insecure_sni` is unset (see commit 41d69a14 and the completed
 * `gate-fake-sni-cert-bypass-behind-allow-insecure-flag-with-telemetry` task in git history).
 * This validator catches the same misconfiguration at *import* time
 * so users see the error before any connect attempt is made.
 *
 * Operates on a `JsonObject` representation so the validator can be
 * wired into any profile-import path (catalog parser, file import,
 * QR-code import) without pre-committing to a typed schema.
 */
object WsProfileImportValidator {
    enum class ErrorCode {
        /**
         * `fake_sni` is set but `allow_insecure_sni` is missing or
         * `false`. The fake-SNI cover disables standard TLS cert
         * verification; operators must opt in explicitly.
         */
        FAKE_SNI_WITHOUT_ACK,
    }

    data class ValidationError(
        val code: ErrorCode,
        val path: String,
        val message: String,
    )

    fun validate(profile: JsonObject): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val fakeSni = (profile["fake_sni"] as? JsonPrimitive)?.content
        if (!fakeSni.isNullOrEmpty()) {
            val acknowledged = (profile["allow_insecure_sni"] as? JsonPrimitive)?.booleanOrNull == true
            if (!acknowledged) {
                errors +=
                    ValidationError(
                        code = ErrorCode.FAKE_SNI_WITHOUT_ACK,
                        path = "allow_insecure_sni",
                        message =
                            "fake_sni is set but allow_insecure_sni is not true; the " +
                                "TLS certificate verification bypass requires explicit operator " +
                                "acknowledgement. Set allow_insecure_sni:true to enable.",
                    )
            }
        }
        return errors
    }
}
