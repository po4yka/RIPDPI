package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceTelemetrySnapshot
import com.poyka.ripdpi.diagnostics.NetworkPathValidationEvidence
import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.ZERO

class VpnDataPlaneStatusProjectionTest {
    @Test
    fun `running lifecycle remains separate from vpn data plane evidence`() {
        val workingEvidence =
            NetworkPathValidationEvidence(
                captureStatus = "captured",
                vpnPresent = true,
                vpnInternet = true,
                vpnValidated = true,
                vpnCaptivePortal = false,
            )
        val unavailableEvidence = workingEvidence.copy(vpnValidated = false)
        val missingVpnEvidence = workingEvidence.copy(vpnPresent = false)
        val noInternetEvidence = workingEvidence.copy(vpnInternet = false)
        val captivePortalEvidence = workingEvidence.copy(vpnCaptivePortal = true)
        val incompleteEvidence = workingEvidence.copy(vpnValidated = null)
        val unavailableCapture = NetworkPathValidationEvidence(captureStatus = "permission_unavailable")

        assertEquals(
            listOf(
                AppStatus.Running to VpnDataPlaneStatus.Working,
                AppStatus.Running to VpnDataPlaneStatus.Unavailable,
                AppStatus.Running to VpnDataPlaneStatus.Unavailable,
                AppStatus.Running to VpnDataPlaneStatus.Unavailable,
                AppStatus.Running to VpnDataPlaneStatus.Unavailable,
                AppStatus.Running to VpnDataPlaneStatus.Checking,
                AppStatus.Running to VpnDataPlaneStatus.Unverified,
                AppStatus.Running to VpnDataPlaneStatus.NotApplicable,
                AppStatus.Halted to VpnDataPlaneStatus.NotApplicable,
            ),
            listOf(
                AppStatus.Running to resolveVpnDataPlaneStatus(AppStatus.Running, Mode.VPN, workingEvidence),
                AppStatus.Running to resolveVpnDataPlaneStatus(AppStatus.Running, Mode.VPN, unavailableEvidence),
                AppStatus.Running to resolveVpnDataPlaneStatus(AppStatus.Running, Mode.VPN, missingVpnEvidence),
                AppStatus.Running to resolveVpnDataPlaneStatus(AppStatus.Running, Mode.VPN, noInternetEvidence),
                AppStatus.Running to resolveVpnDataPlaneStatus(AppStatus.Running, Mode.VPN, captivePortalEvidence),
                AppStatus.Running to resolveVpnDataPlaneStatus(AppStatus.Running, Mode.VPN, incompleteEvidence),
                AppStatus.Running to resolveVpnDataPlaneStatus(AppStatus.Running, Mode.VPN, unavailableCapture),
                AppStatus.Running to resolveVpnDataPlaneStatus(AppStatus.Running, Mode.Proxy, workingEvidence),
                AppStatus.Halted to resolveVpnDataPlaneStatus(AppStatus.Halted, Mode.VPN, workingEvidence),
            ),
        )
    }

    @Test
    fun `home keeps local vpn runtime active without claiming unavailable data plane works`() {
        val stringResolver = FakeStringResolver()
        val actuator =
            buildConnectionActuatorUiState(
                settings = AppSettings.newBuilder().build(),
                activeMode = Mode.VPN,
                configuredMode = Mode.VPN,
                connectionState = ConnectionState.Connected,
                vpnDataPlaneStatus = VpnDataPlaneStatus.Unavailable,
                runtime = ConnectionRuntimeState(connectionState = ConnectionState.Connected),
                telemetry = ServiceTelemetrySnapshot(),
                approachSummary = null,
                stringResolver = stringResolver,
            )

        assertEquals(
            Triple(
                HomeConnectionActuatorStatus.Degraded,
                HomeConnectionActuatorStageState.Warning,
                stringResolver.getString(R.string.home_connection_actuator_state_vpn_unavailable),
            ),
            Triple(
                actuator.status,
                actuator.stages.single { it.stage == HomeConnectionActuatorStage.Route }.state,
                actuator.statusDescription,
            ),
        )
    }

    @Test
    fun `vpn card exposes local runtime and unavailable vpn connectivity separately`() {
        val stringResolver = FakeStringResolver()
        val card =
            buildHomeModeCards(
                HomeModeCardsInput(
                    settings = AppSettings.newBuilder().build(),
                    activeMode = Mode.VPN,
                    configuredMode = Mode.VPN,
                    connectionState = ConnectionState.Connected,
                    vpnDataPlaneStatus = VpnDataPlaneStatus.Unavailable,
                    connectionDuration = ZERO,
                    homeDiagnostics = HomeDiagnosticsUiState(),
                    stringResolver = stringResolver,
                ),
            ).single { it.mode == HomeMode.RemoteVpn }

        assertEquals(
            listOf(
                true,
                stringResolver.getString(R.string.home_connection_actuator_state_vpn_unavailable),
                stringResolver.getString(R.string.home_connection_actuator_state_vpn_unavailable),
            ),
            listOf(card.isActive, card.secondaryLabel, card.statusLine),
        )
    }
}
