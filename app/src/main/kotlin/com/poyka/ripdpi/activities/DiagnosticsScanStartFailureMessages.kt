package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.InPathRouteUnavailableReason
import com.poyka.ripdpi.diagnostics.DiagnosticsInPathUnavailableReason

internal fun DiagnosticsInPathUnavailableReason.inPathFailureMessageResource(): Int =
    when (this) {
        DiagnosticsInPathUnavailableReason.ProxyEndpointMismatch -> {
            R.string.diagnostics_in_path_endpoint_mismatch
        }

        is DiagnosticsInPathUnavailableReason.Route -> {
            when (unavailable.reason) {
                InPathRouteUnavailableReason.RuntimeAbsent -> R.string.diagnostics_in_path_runtime_absent
                InPathRouteUnavailableReason.LeaseUnpublished -> R.string.diagnostics_in_path_lease_unpublished
                InPathRouteUnavailableReason.RouteGenerationMismatch -> R.string.diagnostics_in_path_route_changed
                InPathRouteUnavailableReason.RouteEvidenceUnavailable -> R.string.diagnostics_in_path_route_unverified
                InPathRouteUnavailableReason.LeaseRevoked -> R.string.diagnostics_in_path_route_revoked
            }
        }
    }
