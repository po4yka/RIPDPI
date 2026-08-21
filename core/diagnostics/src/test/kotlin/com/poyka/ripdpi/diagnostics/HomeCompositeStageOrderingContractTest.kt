package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCompositeStageOrderingContractTest {
    @Test
    fun `progress declaration separates proxy comparison from passive vpn route evidence`() {
        val canonicalTail = setOf("dpi_full", "path_comparison", "vpn_route_evidence", "dpi_strategy")

        assertEquals(
            listOf("dpi_full", "path_comparison", "vpn_route_evidence", "dpi_strategy"),
            HomeCompositeStageSpecs.map(HomeCompositeStageSpec::key).filter(canonicalTail::contains),
        )

        val comparison = HomeCompositeStageSpecs.single { it.key == "path_comparison" }
        assertEquals("Proxy vs direct path", comparison.label)
        assertEquals(HomeCompositeStageVantage.PROXY_VANTAGE, comparison.vantage)

        val passiveVpn = HomeCompositeStageSpecs.single { it.key == "vpn_route_evidence" }
        assertEquals(null, passiveVpn.pathMode)
        assertEquals(HomeCompositeStageEvidenceType.PASSIVE_VPN_ROUTE, passiveVpn.evidenceType)
        assertEquals(HomeCompositeStageVantage.VPN_ROUTE_OBSERVATION, passiveVpn.vantage)
        assertEquals(HomeCompositeTargetReachability.UNVERIFIED, passiveVpn.targetReachability)
        assertEquals(false, passiveVpn.startsScanSession)
    }
}
