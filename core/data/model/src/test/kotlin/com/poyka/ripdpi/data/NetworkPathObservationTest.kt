package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPathObservationTest {
    @Test
    fun `aggregate counts are bounded and report truncation`() {
        assertEquals(0, boundedNetworkPathCount(-1))
        assertEquals(MaxNetworkPathAggregateCount, boundedNetworkPathCount(MaxNetworkPathAggregateCount + 10))
        assertFalse(networkPathCountWasTruncated(MaxNetworkPathAggregateCount))
        assertTrue(networkPathCountWasTruncated(MaxNetworkPathAggregateCount + 1))
    }

    @Test
    fun `mtu and bandwidth values reduce to stable bands`() {
        assertEquals("unknown", networkPathMtuBand(null))
        assertEquals("sub_ipv6_minimum", networkPathMtuBand(1_279))
        assertEquals("reduced", networkPathMtuBand(1_280))
        assertEquals("standard", networkPathMtuBand(1_500))
        assertEquals("jumbo", networkPathMtuBand(1_501))

        assertEquals("unknown", networkPathBandwidthBand(0))
        assertEquals("low", networkPathBandwidthBand(999))
        assertEquals("medium", networkPathBandwidthBand(1_000))
        assertEquals("high", networkPathBandwidthBand(10_000))
        assertEquals("very_high", networkPathBandwidthBand(100_000))
    }
}
