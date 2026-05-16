package com.poyka.ripdpi.services.redaction

/** Verdict returned by [ClipboardShareGuard.audit]. */
enum class GuardVerdict {
    Allow,
    Warn,
    Clear,
}

/**
 * Audits text that is about to be placed on the clipboard or shared via a
 * share sheet. Returns [GuardVerdict.Warn] when the text contains patterns
 * that look like live profile material (UUIDs, subscription tokens, credential
 * URLs). Returns [GuardVerdict.Allow] when the text is safe to share.
 *
 * [GuardVerdict.Clear] is returned when the text matches a credential URL
 * with embedded user:pass, which must not be forwarded at all.
 */
object ClipboardShareGuard {
    private val UuidPattern =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    private val CredentialUrlPattern =
        Regex("[a-z][a-z0-9+.-]*://[^\\s/@:]+:[^\\s/@]+@", RegexOption.IGNORE_CASE)

    private val TokenPattern =
        Regex("(?i)\\b(token|auth|password|secret)=([^\\s&]{6,})")

    fun audit(text: String): GuardVerdict =
        when {
            CredentialUrlPattern.containsMatchIn(text) -> GuardVerdict.Clear
            UuidPattern.containsMatchIn(text) || TokenPattern.containsMatchIn(text) -> GuardVerdict.Warn
            else -> GuardVerdict.Allow
        }
}
