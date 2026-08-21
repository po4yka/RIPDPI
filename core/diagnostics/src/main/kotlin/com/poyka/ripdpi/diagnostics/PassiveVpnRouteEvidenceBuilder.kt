package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.NetworkPathAssociationActiveDefault
import com.poyka.ripdpi.data.VpnRouteCallbackState
import com.poyka.ripdpi.data.VpnRouteConsistency
import com.poyka.ripdpi.data.VpnRouteEvidence
import com.poyka.ripdpi.data.VpnRouteLifecycleState
import com.poyka.ripdpi.data.VpnRouteOwnerVerification

internal enum class PassiveVpnRouteEvidenceDisposition {
    CAPTURED,
    NOT_VPN_MODE,
    STALE_GENERATION,
    INCOMPLETE_EVIDENCE,
    INELIGIBLE_LIFECYCLE,
    STALE_FORWARDING_GENERATION,
}

internal data class PassiveVpnRouteEvidence(
    val runId: String,
    val disposition: PassiveVpnRouteEvidenceDisposition,
    val pathValidation: NetworkPathValidationEvidence,
    val routeAxis: String,
    val forwardingAxis: String,
)

internal class PassiveVpnRouteEvidenceBuilder {
    fun build(
        runId: String,
        serviceStatus: Pair<AppStatus, Mode>,
        currentGeneration: Long?,
        vpnRouteEvidence: VpnRouteEvidence,
    ): PassiveVpnRouteEvidence {
        val disposition = evidenceDisposition(serviceStatus, currentGeneration, vpnRouteEvidence)
        val snapshot = vpnRouteEvidence.toDiagnosticsSnapshot()
        return PassiveVpnRouteEvidence(
            runId = runId,
            disposition = disposition,
            pathValidation =
                NetworkPathValidationEvidence(
                    captureStatus =
                        if (disposition == PassiveVpnRouteEvidenceDisposition.CAPTURED) {
                            "captured"
                        } else {
                            "rejected"
                        },
                    vpnAssociation = NetworkPathAssociationActiveDefault,
                    vpnPresent = vpnRouteEvidence.vpnPresent,
                    vpnInternet = vpnRouteEvidence.hasInternet,
                    vpnValidated = vpnRouteEvidence.validated,
                    vpnCaptivePortal = vpnRouteEvidence.captivePortal,
                    vpnRouteEvidence = snapshot,
                ),
            routeAxis = snapshot.routeConsistency,
            forwardingAxis = snapshot.forwardingOutcome,
        )
    }

    private fun evidenceDisposition(
        serviceStatus: Pair<AppStatus, Mode>,
        currentGeneration: Long?,
        vpnRouteEvidence: VpnRouteEvidence,
    ): PassiveVpnRouteEvidenceDisposition {
        val lifecycle = vpnRouteEvidence.lifecycle
        return when {
            serviceStatus.first != AppStatus.Running || serviceStatus.second != Mode.VPN -> {
                PassiveVpnRouteEvidenceDisposition.NOT_VPN_MODE
            }

            lifecycle == null || currentGeneration == null -> {
                PassiveVpnRouteEvidenceDisposition.INCOMPLETE_EVIDENCE
            }

            lifecycle.generation != currentGeneration -> {
                PassiveVpnRouteEvidenceDisposition.STALE_GENERATION
            }

            lifecycle.state !in CapturableVpnRouteLifecycleStates -> {
                PassiveVpnRouteEvidenceDisposition.INELIGIBLE_LIFECYCLE
            }

            vpnRouteEvidence.callbackState != VpnRouteCallbackState.Complete ||
                vpnRouteEvidence.ownerVerification != VpnRouteOwnerVerification.Verified ||
                lifecycle.ownPackageExcluded != true ||
                vpnRouteEvidence.routeConsistency != VpnRouteConsistency.Consistent -> {
                PassiveVpnRouteEvidenceDisposition.INCOMPLETE_EVIDENCE
            }

            vpnRouteEvidence.forwardingOutcome != "unavailable" &&
                vpnRouteEvidence.forwardingLifecycleGeneration != lifecycle.generation -> {
                PassiveVpnRouteEvidenceDisposition.STALE_FORWARDING_GENERATION
            }

            else -> {
                PassiveVpnRouteEvidenceDisposition.CAPTURED
            }
        }
    }
}

private val CapturableVpnRouteLifecycleStates =
    setOf(
        VpnRouteLifecycleState.Established,
        VpnRouteLifecycleState.BridgeReady,
    )
