package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.NetworkPathValidationEvidence
import com.poyka.ripdpi.diagnostics.VpnRouteEvidenceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class VpnDataPlaneStatusProjectionTest {
    @Test
    fun `validation-only failure warns on network axis without degrading route`() {
        val evidence =
            NetworkPathValidationEvidence(
                captureStatus = "captured",
                vpnRouteEvidence =
                    VpnRouteEvidenceSnapshot(
                        lifecycleState = "bridge_ready",
                        callbackState = "complete",
                        ownerVerification = "verified",
                        routeConsistency = "consistent",
                        vpnPresent = true,
                        validated = false,
                    ),
            )

        assertEquals(VpnDataPlaneStatus.Working, resolveVpnDataPlaneStatus(AppStatus.Running, Mode.VPN, evidence))
        assertEquals(true, vpnValidationWarning(AppStatus.Running, Mode.VPN, evidence))
    }

    @Test
    fun `terminal current-generation forwarding failure warns on tunnel axis only`() {
        val evidence =
            NetworkPathValidationEvidence(
                captureStatus = "captured",
                vpnRouteEvidence =
                    VpnRouteEvidenceSnapshot(
                        lifecycleGeneration = 8L,
                        lifecycleState = "bridge_ready",
                        callbackState = "complete",
                        ownerVerification = "verified",
                        routeConsistency = "consistent",
                        vpnPresent = true,
                        forwardingOutcome = "tun_ingress_no_upstream",
                        forwardingLifecycleGeneration = 8L,
                        forwardingTerminal = true,
                    ),
            )

        assertEquals(VpnDataPlaneStatus.Working, resolveVpnDataPlaneStatus(AppStatus.Running, Mode.VPN, evidence))
        assertEquals(true, vpnForwardingWarning(AppStatus.Running, Mode.VPN, evidence))
        assertEquals(
            false,
            vpnForwardingWarning(
                AppStatus.Running,
                Mode.VPN,
                evidence.copy(
                    vpnRouteEvidence = evidence.vpnRouteEvidence?.copy(forwardingLifecycleGeneration = 7L),
                ),
            ),
        )
    }

    @Test
    fun `startup route convergence is not a degraded warning`() {
        assertEquals(
            null,
            vpnDataPlaneWarning(ConnectionState.Connected, VpnDataPlaneStatus.Checking),
        )
    }

    @Test
    fun `unverified owner observation is not an authoritative route warning`() {
        assertEquals(
            null,
            vpnDataPlaneWarning(ConnectionState.Connected, VpnDataPlaneStatus.Unverified),
        )
    }

    @Test
    fun `established route is healthy before native bridge readiness`() {
        val evidence =
            NetworkPathValidationEvidence(
                captureStatus = "captured",
                vpnRouteEvidence =
                    VpnRouteEvidenceSnapshot(
                        lifecycleState = "established",
                        callbackState = "complete",
                        ownerVerification = "verified",
                        routeConsistency = "consistent",
                        vpnPresent = true,
                    ),
            )

        assertEquals(
            VpnDataPlaneStatus.Working,
            resolveVpnDataPlaneStatus(AppStatus.Running, Mode.VPN, evidence),
        )
    }

    @Test
    fun `self excluded owner underlay does not override healthy owned vpn route`() {
        val evidence =
            NetworkPathValidationEvidence(
                captureStatus = "captured",
                vpnPresent = false,
                vpnRouteEvidence =
                    VpnRouteEvidenceSnapshot(
                        lifecycleState = "bridge_ready",
                        callbackState = "complete",
                        ownerVerification = "verified",
                        routeConsistency = "consistent",
                        vpnPresent = true,
                    ),
            )

        assertEquals(
            VpnDataPlaneStatus.Working,
            resolveVpnDataPlaneStatus(AppStatus.Running, Mode.VPN, evidence),
        )
    }

    @Test
    fun `running lifecycle stays separate from vpn data plane proof`() {
        val healthy =
            NetworkPathValidationEvidence(
                captureStatus = "captured",
                vpnPresent = false,
                vpnRouteEvidence =
                    VpnRouteEvidenceSnapshot(
                        lifecycleState = "bridge_ready",
                        callbackState = "complete",
                        ownerVerification = "verified",
                        routeConsistency = "consistent",
                        vpnPresent = true,
                        validated = false,
                    ),
            )

        assertEquals(
            listOf(
                AppStatus.Running to VpnDataPlaneStatus.Working,
                AppStatus.Running to VpnDataPlaneStatus.Checking,
                AppStatus.Running to VpnDataPlaneStatus.Unverified,
                AppStatus.Running to VpnDataPlaneStatus.Unavailable,
                AppStatus.Running to VpnDataPlaneStatus.Degraded,
                AppStatus.Running to VpnDataPlaneStatus.NotApplicable,
            ),
            listOf(
                AppStatus.Running to resolveVpnDataPlaneStatus(AppStatus.Running, Mode.VPN, healthy),
                AppStatus.Running to
                    resolveVpnDataPlaneStatus(
                        AppStatus.Running,
                        Mode.VPN,
                        healthy.copy(
                            vpnRouteEvidence =
                                healthy.vpnRouteEvidence?.copy(
                                    lifecycleState = "established",
                                    callbackState = "awaiting",
                                    ownerVerification = "unavailable",
                                ),
                        ),
                    ),
                AppStatus.Running to
                    resolveVpnDataPlaneStatus(
                        AppStatus.Running,
                        Mode.VPN,
                        healthy.copy(
                            vpnRouteEvidence =
                                healthy.vpnRouteEvidence?.copy(
                                    callbackState = "unavailable",
                                    routeConsistency = "unavailable",
                                    vpnPresent = null,
                                ),
                        ),
                    ),
                AppStatus.Running to
                    resolveVpnDataPlaneStatus(
                        AppStatus.Running,
                        Mode.VPN,
                        healthy.copy(
                            vpnRouteEvidence =
                                healthy.vpnRouteEvidence?.copy(
                                    callbackState = "lost",
                                    vpnPresent = false,
                                ),
                        ),
                    ),
                AppStatus.Running to
                    resolveVpnDataPlaneStatus(
                        AppStatus.Running,
                        Mode.VPN,
                        healthy.copy(
                            vpnRouteEvidence =
                                healthy.vpnRouteEvidence?.copy(routeConsistency = "mismatch"),
                        ),
                    ),
                AppStatus.Running to resolveVpnDataPlaneStatus(AppStatus.Running, Mode.Proxy, healthy),
            ),
        )
    }

    @Test
    fun `owned vpn callback timeout remains unverified without a route warning`() {
        val evidence =
            NetworkPathValidationEvidence(
                captureStatus = "captured",
                vpnRouteEvidence =
                    VpnRouteEvidenceSnapshot(
                        lifecycleState = "established",
                        callbackState = "timed_out",
                        ownerVerification = "unavailable",
                        routeConsistency = "unavailable",
                    ),
            )

        assertEquals(
            VpnDataPlaneStatus.Unverified,
            resolveVpnDataPlaneStatus(AppStatus.Running, Mode.VPN, evidence),
        )
        assertEquals(null, vpnDataPlaneWarning(ConnectionState.Connected, VpnDataPlaneStatus.Unverified))
    }
}
