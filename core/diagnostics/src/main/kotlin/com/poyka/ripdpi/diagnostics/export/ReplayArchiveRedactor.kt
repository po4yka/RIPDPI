package com.poyka.ripdpi.diagnostics.export

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Strips IPv4 / IPv6 substrings from replay event detail strings before
 * they land in `replay-results.json` (G010 P4.8).
 *
 * Background: `ReplayStepEvent.StepCompleted` for the DNS step carries
 * `detail = "resolved <ip>"`; persisting raw resolver output would
 * regress the Play Store Data Safety surface per
 * `.claude/rules/network-fingerprint-privacy.md`. The redactor
 * replaces matches with [Placeholder] while preserving the surrounding
 * structure of the string.
 *
 * Over-redaction is preferred to under-redaction — every IPv4 quad
 * (including non-routable / private addresses) and every IPv6 form
 * (compressed and full) is replaced. False positives on numeric tokens
 * are harmless; false negatives are a privacy regression.
 */
@Singleton
class ReplayArchiveRedactor
    @Inject
    constructor() {
        fun redact(detail: String): String =
            detail
                .replace(IPV4_REGEX, Placeholder)
                .replace(IPV6_REGEX, Placeholder)

        companion object {
            const val Placeholder: String = "<IP_REDACTED>"

            // Four dot-separated 1-3 digit groups bracketed by word boundaries.
            // Over-matches "999.999.999.999" — acceptable: false positives are
            // harmless, false negatives are a privacy regression.
            private val IPV4_REGEX: Regex =
                Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""")

            // IPv6: matches either the canonical 8-group form (7 colons) OR the
            // "::" compressed form, bracketed by a lookbehind/lookahead so we
            // do NOT redact ordinary "hh:mm:ss" timestamps or "port:443"
            // tokens. Compressed form covers "::1", "fe80::1", "2001:db8::1",
            // "2001:db8::a:b:c". Requires a literal "::" or 7 single-colon
            // separators, so plain colon-separated numerics (timestamps,
            // host:port) do not match.
            private val IPV6_REGEX: Regex =
                Regex(
                    """(?<![A-Za-z0-9:])""" +
                        """(?:""" +
                        """(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}""" +
                        """|""" +
                        """(?:[0-9a-fA-F]{1,4})?(?::[0-9a-fA-F]{1,4})*::""" +
                        """(?:[0-9a-fA-F]{1,4})?(?::[0-9a-fA-F]{1,4})*""" +
                        """)(?![A-Za-z0-9:])""",
                )
        }
    }
