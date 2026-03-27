package com.poyka.ripdpi.diagnostics

enum class DiagnosticsScanStartRejectionReason {
    HiddenAutomaticProbeRunning,
    ScanAlreadyActive,
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
        },
    )
