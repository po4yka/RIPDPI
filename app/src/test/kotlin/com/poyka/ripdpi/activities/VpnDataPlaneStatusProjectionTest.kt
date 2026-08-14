package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.NetworkPathValidationEvidence
import org.junit.Assert.assertEquals
import org.junit.Test

class VpnDataPlaneStatusProjectionTest {
    @Test
    fun `running lifecycle stays separate from vpn data plane proof`() {
        val captured =
            NetworkPathValidationEvidence(
                captureStatus = "captured",
                vpnPresent = true,
                vpnInternet = true,
                vpnValidated = true,
                vpnCaptivePortal = false,
            )

        assertEquals(
            listOf(
                AppStatus.Running to VpnDataPlaneStatus.Working,
                AppStatus.Running to VpnDataPlaneStatus.Checking,
                AppStatus.Running to VpnDataPlaneStatus.Unverified,
                AppStatus.Running to VpnDataPlaneStatus.Unavailable,
                AppStatus.Running to VpnDataPlaneStatus.NotApplicable,
            ),
            listOf(
                AppStatus.Running to resolveVpnDataPlaneStatus(AppStatus.Running, Mode.VPN, captured),
                AppStatus.Running to
                    resolveVpnDataPlaneStatus(AppStatus.Running, Mode.VPN, captured.copy(vpnValidated = null)),
                AppStatus.Running to
                    resolveVpnDataPlaneStatus(
                        AppStatus.Running,
                        Mode.VPN,
                        NetworkPathValidationEvidence(captureStatus = "permission_unavailable"),
                    ),
                AppStatus.Running to
                    resolveVpnDataPlaneStatus(AppStatus.Running, Mode.VPN, captured.copy(vpnValidated = false)),
                AppStatus.Running to resolveVpnDataPlaneStatus(AppStatus.Running, Mode.Proxy, captured),
            ),
        )
    }
}
