package com.poyka.ripdpi.services.redaction

private const val RedactedPlaceholder = "redacted"

/**
 * Deny-by-default redactor for [DiagnosticsBundle].
 *
 * Every field carrying a [SensitiveTag] category is replaced with the
 * string literal "redacted". Fields not associated with a sensitive category
 * are forwarded unchanged.
 */
object DiagnosticsRedactor {
    fun redact(bundle: DiagnosticsBundle): DiagnosticsBundle =
        bundle.copy(
            uuid = bundle.uuid?.let { RedactedPlaceholder },
            shortId = bundle.shortId?.let { RedactedPlaceholder },
            password = bundle.password?.let { RedactedPlaceholder },
            subscriptionToken = bundle.subscriptionToken?.let { RedactedPlaceholder },
            endpoint = bundle.endpoint?.let { RedactedPlaceholder },
        )
}
