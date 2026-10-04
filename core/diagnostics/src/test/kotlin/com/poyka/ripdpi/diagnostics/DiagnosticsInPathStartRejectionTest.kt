package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.InPathRouteEvidenceAxes
import com.poyka.ripdpi.data.InPathRouteLeaseAcquisition
import com.poyka.ripdpi.data.InPathRouteUnavailableReason
import com.poyka.ripdpi.data.VpnRouteCallbackState
import com.poyka.ripdpi.data.VpnRouteConsistency
import com.poyka.ripdpi.data.VpnRouteLifecycleState
import com.poyka.ripdpi.data.VpnRouteOwnerVerification
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsInPathStartRejectionTest {
    @Test
    fun `public failure evidence includes only fixed category and whitelisted state axes`() {
        val reason =
            DiagnosticsInPathUnavailableReason.Route(
                InPathRouteLeaseAcquisition.Unavailable(
                    InPathRouteUnavailableReason.RouteEvidenceUnavailable,
                    InPathRouteEvidenceAxes(
                        VpnRouteLifecycleState.BridgeReady,
                        VpnRouteCallbackState.Awaiting,
                        VpnRouteOwnerVerification.Unavailable,
                        VpnRouteConsistency.Mismatch,
                        false,
                    ),
                ),
            )
        assertEquals(
            "in_path_start_rejected reason=RouteEvidenceUnavailable lifecycle=BridgeReady " +
                "callback=Awaiting owner_verification=Unavailable consistency=Mismatch forwarding_terminal=false",
            reason.identifierFreeEvidence(),
        )
        assertEquals("In-path diagnostics unavailable: RouteEvidenceUnavailable", inPathStartRejected(reason).message)
        assertEquals(
            "in_path_start_rejected reason=ProxyEndpointMismatch",
            DiagnosticsInPathUnavailableReason.ProxyEndpointMismatch.identifierFreeEvidence(),
        )
    }

    @Test
    fun `all typed route failures preserve the four home stage classifications`() {
        val expected =
            mapOf(
                InPathRouteUnavailableReason.RuntimeAbsent to
                    DiagnosticsHomeCompositeStageUnavailableReason.SERVICE_NOT_RUNNING,
                InPathRouteUnavailableReason.LeaseUnpublished to
                    DiagnosticsHomeCompositeStageUnavailableReason.ACTIVE_VPN_PATH_NOT_OBSERVED,
                InPathRouteUnavailableReason.RouteEvidenceUnavailable to
                    DiagnosticsHomeCompositeStageUnavailableReason.ACTIVE_VPN_PATH_NOT_OBSERVED,
                InPathRouteUnavailableReason.RouteGenerationMismatch to
                    DiagnosticsHomeCompositeStageUnavailableReason.RUNTIME_CHANGED_OR_UNAVAILABLE,
                InPathRouteUnavailableReason.LeaseRevoked to
                    DiagnosticsHomeCompositeStageUnavailableReason.RUNTIME_CHANGED_OR_UNAVAILABLE,
            )
        for ((reason, classification) in expected) {
            assertEquals(
                classification,
                DiagnosticsInPathUnavailableReason
                    .Route(
                        InPathRouteLeaseAcquisition.Unavailable(reason),
                    ).toHomeStageReason(),
            )
        }
        assertEquals(
            DiagnosticsHomeCompositeStageUnavailableReason.PROXY_ENDPOINT_MISMATCH,
            DiagnosticsInPathUnavailableReason.ProxyEndpointMismatch.toHomeStageReason(),
        )
    }
}
