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
    Degraded,
}

internal fun vpnDataPlaneWarning(
    connectionState: ConnectionState,
    status: VpnDataPlaneStatus,
): VpnDataPlaneStatus? =
    status.takeIf {
        connectionState == ConnectionState.Connected &&
            (it == VpnDataPlaneStatus.Unavailable || it == VpnDataPlaneStatus.Degraded)
    }

internal fun vpnValidationWarning(
    appStatus: AppStatus,
    activeMode: Mode,
    evidence: NetworkPathValidationEvidence?,
): Boolean {
    val routeEvidence = evidence?.vpnRouteEvidence
    return appStatus == AppStatus.Running &&
        activeMode == Mode.VPN &&
        routeEvidence?.callbackState == "complete" &&
        routeEvidence.vpnPresent == true &&
        (routeEvidence.validated == false || routeEvidence.captivePortal == true)
}

internal fun vpnForwardingWarning(
    appStatus: AppStatus,
    activeMode: Mode,
    evidence: NetworkPathValidationEvidence?,
): Boolean {
    val routeEvidence = evidence?.vpnRouteEvidence
    return appStatus == AppStatus.Running &&
        activeMode == Mode.VPN &&
        routeEvidence?.forwardingTerminal == true &&
        routeEvidence.forwardingLifecycleGeneration == routeEvidence.lifecycleGeneration &&
        routeEvidence.forwardingOutcome in TerminalForwardingFailures
}

private val TerminalForwardingFailures =
    setOf(
        "fail_closed",
        "tun_ingress_no_upstream",
        "upstream_open_no_payload",
        "outbound_only",
    )

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
): VpnDataPlaneStatus {
    val routeEvidence = evidence?.vpnRouteEvidence
    return when {
        appStatus != AppStatus.Running || activeMode != Mode.VPN -> {
            VpnDataPlaneStatus.NotApplicable
        }

        routeEvidence == null -> {
            VpnDataPlaneStatus.Unverified
        }

        routeEvidence.callbackState == "lost" || routeEvidence.lifecycleState == "closed" -> {
            VpnDataPlaneStatus.Unavailable
        }

        routeEvidence.callbackState == "timed_out" -> {
            VpnDataPlaneStatus.Unverified
        }

        routeEvidence.lifecycleState == "intended" || routeEvidence.callbackState == "awaiting" -> {
            VpnDataPlaneStatus.Checking
        }

        routeEvidence.callbackState == "unavailable" ||
            routeEvidence.ownerVerification != "verified" -> {
            VpnDataPlaneStatus.Unverified
        }

        routeEvidence.callbackState == "complete" &&
            routeEvidence.vpnPresent == true &&
            routeEvidence.routeConsistency == "consistent" -> {
            VpnDataPlaneStatus.Working
        }

        routeEvidence.callbackState == "complete" &&
            routeEvidence.vpnPresent == true &&
            routeEvidence.routeConsistency == "mismatch" -> {
            VpnDataPlaneStatus.Degraded
        }

        else -> {
            VpnDataPlaneStatus.Unverified
        }
    }
}
