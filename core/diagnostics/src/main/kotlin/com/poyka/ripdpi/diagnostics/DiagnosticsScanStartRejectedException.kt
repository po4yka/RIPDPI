package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.InPathRouteLeaseAcquisition

sealed interface DiagnosticsScanStartRejectionReason {
    data object HiddenAutomaticProbeRunning : DiagnosticsScanStartRejectionReason

    data object ScanAlreadyActive : DiagnosticsScanStartRejectionReason

    data object SensitiveProfileConsentRequired : DiagnosticsScanStartRejectionReason

    data object BlockedByLegalSafetyPolicy : DiagnosticsScanStartRejectionReason

    data class InPathUnavailable(
        val reason: DiagnosticsInPathUnavailableReason,
    ) : DiagnosticsScanStartRejectionReason
}

sealed interface DiagnosticsInPathUnavailableReason {
    data class Route(
        val unavailable: InPathRouteLeaseAcquisition.Unavailable,
    ) : DiagnosticsInPathUnavailableReason

    data object ProxyEndpointMismatch : DiagnosticsInPathUnavailableReason
}

class DiagnosticsScanStartRejectedException(
    val reason: DiagnosticsScanStartRejectionReason,
) : IllegalStateException(
        when (reason) {
            is DiagnosticsScanStartRejectionReason.InPathUnavailable -> {
                when (val unavailable = reason.reason) {
                    is DiagnosticsInPathUnavailableReason.Route -> {
                        "In-path diagnostics unavailable: ${unavailable.unavailable.reason.name}"
                    }

                    DiagnosticsInPathUnavailableReason.ProxyEndpointMismatch -> {
                        "In-path diagnostics unavailable: ProxyEndpointMismatch"
                    }
                }
            }

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
