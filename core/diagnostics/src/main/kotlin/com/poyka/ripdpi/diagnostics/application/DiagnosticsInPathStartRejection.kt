@file:Suppress("detekt.InvalidPackageDeclaration")

package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.InPathRouteUnavailableReason

internal fun inPathStartRejected(reason: DiagnosticsInPathUnavailableReason): DiagnosticsScanStartRejectedException {
    Logger.withTag("DiagnosticsScanStart").w { reason.identifierFreeEvidence() }
    return DiagnosticsScanStartRejectedException(DiagnosticsScanStartRejectionReason.InPathUnavailable(reason))
}

internal fun DiagnosticsInPathUnavailableReason.identifierFreeEvidence(): String =
    when (this) {
        DiagnosticsInPathUnavailableReason.ProxyEndpointMismatch -> {
            "in_path_start_rejected reason=ProxyEndpointMismatch"
        }

        is DiagnosticsInPathUnavailableReason.Route -> {
            buildString {
                append("in_path_start_rejected reason=${unavailable.reason.name}")
                unavailable.evidence?.let { axes ->
                    append(" lifecycle=${axes.lifecycle?.name ?: "Absent"}")
                    append(" callback=${axes.callback.name}")
                    append(" owner_verification=${axes.ownerVerification.name}")
                    append(" consistency=${axes.consistency.name}")
                    append(" forwarding_terminal=${axes.forwardingTerminal}")
                }
            }
        }
    }

internal fun DiagnosticsInPathUnavailableReason.toHomeStageReason(): DiagnosticsHomeCompositeStageUnavailableReason =
    when (this) {
        DiagnosticsInPathUnavailableReason.ProxyEndpointMismatch -> {
            DiagnosticsHomeCompositeStageUnavailableReason.PROXY_ENDPOINT_MISMATCH
        }

        is DiagnosticsInPathUnavailableReason.Route -> {
            when (unavailable.reason) {
                InPathRouteUnavailableReason.RuntimeAbsent -> {
                    DiagnosticsHomeCompositeStageUnavailableReason.SERVICE_NOT_RUNNING
                }

                InPathRouteUnavailableReason.LeaseUnpublished,
                InPathRouteUnavailableReason.RouteEvidenceUnavailable,
                -> {
                    DiagnosticsHomeCompositeStageUnavailableReason.ACTIVE_VPN_PATH_NOT_OBSERVED
                }

                InPathRouteUnavailableReason.RouteGenerationMismatch,
                InPathRouteUnavailableReason.LeaseRevoked,
                -> {
                    DiagnosticsHomeCompositeStageUnavailableReason.RUNTIME_CHANGED_OR_UNAVAILABLE
                }
            }
        }
    }
