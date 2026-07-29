package com.poyka.ripdpi.diagnostics.export

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies the strict archive redaction policy to replay event details before
 * they land in `replay-results.json`.
 *
 * Background: `ReplayStepEvent.StepCompleted` for the DNS step carries
 * `detail = "resolved <ip>"`; persisting raw resolver output would
 * regress the Play Store Data Safety surface per
 * `.claude/rules/network-fingerprint-privacy.md`. The redactor
 * replaces sensitive endpoints, credentials, paths, certificate material,
 * hostnames, and IP addresses while preserving safe diagnostic structure.
 *
 * Over-redaction is preferred to under-redaction.
 */
@Singleton
class ReplayArchiveRedactor
    @Inject
    constructor() {
        fun redact(detail: String): String =
            redactDiagnosticsArchiveText(detail)
                .replace("<ip-redacted>", Placeholder)

        companion object {
            const val Placeholder: String = "<IP_REDACTED>"
        }
    }
