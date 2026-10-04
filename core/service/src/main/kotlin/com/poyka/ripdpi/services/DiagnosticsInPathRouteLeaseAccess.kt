package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.DiagnosticsInPathRouteLease
import com.poyka.ripdpi.data.InPathRouteEvidenceAxes
import com.poyka.ripdpi.data.InPathRouteLeaseAcquisition
import com.poyka.ripdpi.data.InPathRouteUnavailableReason
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.VpnRouteEvidence
import com.poyka.ripdpi.data.VpnRouteEvidenceProvider

/** Issues and validates revision-bound access to the current authenticated VPN diagnostics route. */
internal class DiagnosticsInPathRouteLeaseAccess(
    private val serviceRuntimeRegistry: ServiceRuntimeRegistry,
    private val serviceStateStore: ServiceStateStore,
    private val vpnRouteEvidenceProvider: VpnRouteEvidenceProvider,
) {
    fun acquire(): InPathRouteLeaseAcquisition {
        val runtime = serviceRuntimeRegistry.current(Mode.VPN)
        return when {
            runtime == null || serviceStateStore.status.value != (AppStatus.Running to Mode.VPN) -> {
                InPathRouteLeaseAcquisition.Unavailable(InPathRouteUnavailableReason.RuntimeAbsent)
            }

            else -> {
                val published = runtime.diagnosticsInPathRouteLease
                if (published == null) {
                    InPathRouteLeaseAcquisition.Unavailable(InPathRouteUnavailableReason.LeaseUnpublished)
                } else {
                    issueInPathRouteLease(published)
                }
            }
        }
    }

    private fun issueInPathRouteLease(published: DiagnosticsInPathRouteLease): InPathRouteLeaseAcquisition {
        val evidence = vpnRouteEvidenceProvider.capture()
        val unavailable = routeUnavailability(published, evidence)
        return if (unavailable != null) {
            unavailable
        } else {
            val issued = published.copy(issuedRevision = evidence.callbackRevision)
            validate(issued) ?: InPathRouteLeaseAcquisition.Acquired(issued)
        }
    }

    fun validate(lease: DiagnosticsInPathRouteLease): InPathRouteLeaseAcquisition.Unavailable? {
        if (lease.issuedRevision == null) {
            return InPathRouteLeaseAcquisition.Unavailable(InPathRouteUnavailableReason.LeaseRevoked)
        }
        val evidence = vpnRouteEvidenceProvider.capture()
        return routeUnavailability(lease, evidence) ?: if (
            lease.issuedRevision != evidence.callbackRevision ||
            serviceRuntimeRegistry.current(Mode.VPN)?.diagnosticsInPathRouteLease !=
            lease.copy(issuedRevision = null)
        ) {
            InPathRouteLeaseAcquisition.Unavailable(InPathRouteUnavailableReason.LeaseRevoked)
        } else {
            null
        }
    }

    private fun routeUnavailability(
        lease: DiagnosticsInPathRouteLease,
        evidence: VpnRouteEvidence,
    ): InPathRouteLeaseAcquisition.Unavailable? =
        when {
            serviceStateStore.status.value != (AppStatus.Running to Mode.VPN) -> {
                InPathRouteLeaseAcquisition.Unavailable(InPathRouteUnavailableReason.RuntimeAbsent)
            }

            evidence.lifecycle != null && evidence.lifecycle?.generation != lease.routeGeneration -> {
                InPathRouteLeaseAcquisition.Unavailable(InPathRouteUnavailableReason.RouteGenerationMismatch)
            }

            evidence.lifecycle?.generation != lease.routeGeneration || !evidence.isEligibleForInPathLease() -> {
                InPathRouteLeaseAcquisition.Unavailable(
                    InPathRouteUnavailableReason.RouteEvidenceUnavailable,
                    InPathRouteEvidenceAxes(
                        evidence.lifecycle?.state,
                        evidence.callbackState,
                        evidence.ownerVerification,
                        evidence.routeConsistency,
                        evidence.forwardingTerminal,
                    ),
                )
            }

            else -> {
                null
            }
        }
}
