package com.poyka.ripdpi.e2e

import org.junit.Assert.assertEquals
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
    fun defaultNetworkStateCodePreservesUnknown() {
        assertEquals(
            TestProbeDefaultNetworkState.Unknown,
            TestProbeDefaultNetworkState.fromCode(3),
        )
        assertEquals(
            null,
            TestProbeDefaultNetworkState.fromCode(null),
        )
    }

    @Test
    fun defaultNetworkStateCodesMapVpnAndNonVpn() {
        assertEquals(
            TestProbeDefaultNetworkState.Vpn,
            TestProbeDefaultNetworkState.fromCode(1),
        )
        assertEquals(
            TestProbeDefaultNetworkState.NonVpn,
            TestProbeDefaultNetworkState.fromCode(2),
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
