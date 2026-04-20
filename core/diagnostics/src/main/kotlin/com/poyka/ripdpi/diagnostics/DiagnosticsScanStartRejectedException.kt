package com.poyka.ripdpi.diagnostics

enum class DiagnosticsScanStartRejectionReason {
    HiddenAutomaticProbeRunning,
    ScanAlreadyActive,
    SensitiveProfileConsentRequired,
    BlockedByLegalSafetyPolicy,
}

class DiagnosticsScanStartRejectedException(
    val reason: DiagnosticsScanStartRejectionReason,
) : IllegalStateException(
        when (reason) {
            DiagnosticsScanStartRejectionReason.HiddenAutomaticProbeRunning -> {
                "Automatic probing is already running"
            }

            DiagnosticsScanStartRejectionReason.ScanAlreadyActive -> {
                "Diagnostics scan already active"
            }

            DiagnosticsScanStartRejectionReason.SensitiveProfileConsentRequired -> {
                "Explicit consent is required before running this diagnostics profile"
            }

            DiagnosticsScanStartRejectionReason.BlockedByLegalSafetyPolicy -> {
                "Diagnostics profile is unavailable under local legal-safety policy"
            }
        },
    )
