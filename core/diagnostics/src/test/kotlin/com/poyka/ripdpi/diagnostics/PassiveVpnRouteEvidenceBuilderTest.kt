package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.VpnRouteAppRoutingShape
import com.poyka.ripdpi.data.VpnRouteCallbackState
import com.poyka.ripdpi.data.VpnRouteConsistency
import com.poyka.ripdpi.data.VpnRouteEvidence
import com.poyka.ripdpi.data.VpnRouteLifecycleReceipt
import com.poyka.ripdpi.data.VpnRouteLifecycleState
import com.poyka.ripdpi.data.VpnRouteOwnerVerification
import org.junit.Assert.assertEquals
import org.junit.Test

class PassiveVpnRouteEvidenceBuilderTest {
    @Test
    fun `fail closed lifecycle is rejected despite complete owner callback`() {
        val evidence =
            PassiveVpnRouteEvidenceBuilder().build(
                runId = "run",
                serviceStatus = AppStatus.Running to Mode.VPN,
                currentGeneration = 7L,
                vpnRouteEvidence = vpnRouteEvidence(VpnRouteLifecycleState.FailClosed),
            )

        assertEquals(PassiveVpnRouteEvidenceDisposition.INELIGIBLE_LIFECYCLE, evidence.disposition)
    }

    @Test
    fun `route that includes the owner package is rejected`() {
        val routeEvidence = vpnRouteEvidence(VpnRouteLifecycleState.BridgeReady)
        val evidence =
            PassiveVpnRouteEvidenceBuilder().build(
                runId = "run",
                serviceStatus = AppStatus.Running to Mode.VPN,
                currentGeneration = 7L,
                vpnRouteEvidence =
                    routeEvidence.copy(
                        lifecycle =
                            requireNotNull(routeEvidence.lifecycle).copy(
                                ownPackageExcluded = false,
                            ),
                    ),
            )

        assertEquals(PassiveVpnRouteEvidenceDisposition.INCOMPLETE_EVIDENCE, evidence.disposition)
    }

    @Test
    fun `route consistency mismatch is rejected as incomplete evidence`() {
        val evidence =
            PassiveVpnRouteEvidenceBuilder().build(
                runId = "run",
                serviceStatus = AppStatus.Running to Mode.VPN,
                currentGeneration = 7L,
                vpnRouteEvidence =
                    vpnRouteEvidence(VpnRouteLifecycleState.BridgeReady).copy(
                        routeConsistency = VpnRouteConsistency.Mismatch,
                    ),
            )

        assertEquals(PassiveVpnRouteEvidenceDisposition.INCOMPLETE_EVIDENCE, evidence.disposition)
    }
}

private fun vpnRouteEvidence(state: VpnRouteLifecycleState) =
    VpnRouteEvidence(
        lifecycle =
            VpnRouteLifecycleReceipt(
                generation = 7L,
                state = state,
                intendedDefaultRouteFamilies = listOf("ipv4"),
                addressFamilies = listOf("ipv4"),
                dnsServerFamilies = listOf("ipv4"),
                appRoutingShape = VpnRouteAppRoutingShape.Disallow,
                configuredAppCount = 1,
                ownPackageExcluded = true,
                ipv6Intent = false,
                mtuBand = "standard",
                metered = false,
            ),
        callbackState = VpnRouteCallbackState.Complete,
        ownerVerification = VpnRouteOwnerVerification.Verified,
        routeConsistency = VpnRouteConsistency.Consistent,
        forwardingOutcome = "fail_closed",
        forwardingLifecycleGeneration = 7L,
        forwardingTerminal = true,
    )
