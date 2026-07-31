package com.poyka.ripdpi.e2e

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestNetworkProbeServiceContractTest {
    @Test
    fun usableNonVpnDefaultNetworkRejectsMissingOrVpnCapabilities() {
        assertFalse(
            isUsableNonVpnDefaultNetwork(
                capabilitiesPresent = false,
                hasVpnTransport = false,
                hasNotVpnCapability = false,
            ),
        )
        assertFalse(
            isUsableNonVpnDefaultNetwork(
                capabilitiesPresent = true,
                hasVpnTransport = true,
                hasNotVpnCapability = true,
            ),
        )
    }

    @Test
    fun usableNonVpnDefaultNetworkRequiresTheNotVpnCapability() {
        assertFalse(
            isUsableNonVpnDefaultNetwork(
                capabilitiesPresent = true,
                hasVpnTransport = false,
                hasNotVpnCapability = false,
            ),
        )
        assertTrue(
            isUsableNonVpnDefaultNetwork(
                capabilitiesPresent = true,
                hasVpnTransport = false,
                hasNotVpnCapability = true,
            ),
        )
    }
}
