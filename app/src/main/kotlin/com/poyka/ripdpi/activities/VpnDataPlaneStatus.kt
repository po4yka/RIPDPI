package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.NetworkPathValidationEvidence
import com.poyka.ripdpi.platform.StringResolver

/** Observed VPN data-plane state, independent of whether local service processes are running. */
internal enum class VpnDataPlaneStatus {
    NotApplicable,
    Checking,
    Working,
    Unverified,
    Unavailable,
}

internal fun vpnDataPlaneWarning(
    connectionState: ConnectionState,
    status: VpnDataPlaneStatus,
): VpnDataPlaneStatus? =
    status.takeIf {
        connectionState == ConnectionState.Connected &&
            it != VpnDataPlaneStatus.Working &&
            it != VpnDataPlaneStatus.NotApplicable
    }

internal fun vpnDataPlaneActuatorDescription(
    status: VpnDataPlaneStatus?,
    stringResolver: StringResolver,
): String? =
    when (status) {
        VpnDataPlaneStatus.Checking -> {
            stringResolver.getString(R.string.home_connection_actuator_state_vpn_checking)
        }

        VpnDataPlaneStatus.Unverified -> {
            stringResolver.getString(R.string.home_connection_actuator_state_vpn_unverified)
        }

        VpnDataPlaneStatus.Unavailable -> {
            stringResolver.getString(R.string.home_connection_actuator_state_vpn_unavailable)
        }

        else -> {
            null
        }
    }

internal fun resolveVpnDataPlaneStatus(
    appStatus: AppStatus,
    activeMode: Mode,
    evidence: NetworkPathValidationEvidence?,
): VpnDataPlaneStatus =
    when {
        appStatus != AppStatus.Running || activeMode != Mode.VPN -> {
            VpnDataPlaneStatus.NotApplicable
        }

        evidence?.captureStatus != "captured" -> {
            VpnDataPlaneStatus.Unverified
        }

        evidence.vpnPresent == false ||
            evidence.vpnInternet == false ||
            evidence.vpnValidated == false ||
            evidence.vpnCaptivePortal == true -> {
            VpnDataPlaneStatus.Unavailable
        }

        evidence.vpnPresent == true && evidence.vpnInternet == true && evidence.vpnValidated == true -> {
            VpnDataPlaneStatus.Working
        }

        else -> {
            VpnDataPlaneStatus.Checking
        }
    }
