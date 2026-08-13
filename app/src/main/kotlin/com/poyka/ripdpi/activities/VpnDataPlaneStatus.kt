package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.diagnostics.NetworkPathValidationEvidence

/** UI projection of observed VPN data-plane health, independent of the local service lifecycle. */
internal enum class VpnDataPlaneStatus {
    NotApplicable,
    Checking,
    Working,
    Unverified,
    Unavailable,
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

        evidence.vpnPresent == true &&
            evidence.vpnInternet == true &&
            evidence.vpnValidated == true -> {
            VpnDataPlaneStatus.Working
        }

        else -> {
            VpnDataPlaneStatus.Checking
        }
    }
