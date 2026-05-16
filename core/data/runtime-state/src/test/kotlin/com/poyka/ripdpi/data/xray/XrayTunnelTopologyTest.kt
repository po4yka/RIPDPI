package com.poyka.ripdpi.data.xray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [XrayTunnelTopology] sealed class and its default variant.
 */
class XrayTunnelTopologyTest {
    @Test
    fun `default topology is TunToLocalInbound`() {
        val config = XrayProviderConfig()
        assertEquals(XrayTunnelTopology.TunToLocalInbound, config.tunnelTopology)
    }

    @Test
    fun `TunToLocalInbound variant is selectable`() {
        val config = XrayProviderConfig(tunnelTopology = XrayTunnelTopology.TunToLocalInbound)
        assertTrue(config.tunnelTopology is XrayTunnelTopology.TunToLocalInbound)
    }

    @Test
    fun `LibXraySetTunFd variant is selectable`() {
        val config = XrayProviderConfig(tunnelTopology = XrayTunnelTopology.LibXraySetTunFd)
        assertTrue(config.tunnelTopology is XrayTunnelTopology.LibXraySetTunFd)
    }

    @Test
    fun `when expression over XrayTunnelTopology is exhaustive`() {
        val topologies: List<XrayTunnelTopology> =
            listOf(
                XrayTunnelTopology.TunToLocalInbound,
                XrayTunnelTopology.LibXraySetTunFd,
            )
        val covered = mutableSetOf<String>()
        for (topology in topologies) {
            val label =
                when (topology) {
                    is XrayTunnelTopology.TunToLocalInbound -> "tun_to_local"
                    is XrayTunnelTopology.LibXraySetTunFd -> "lib_xray_set_tun_fd"
                }
            covered.add(label)
        }
        assertEquals(setOf("tun_to_local", "lib_xray_set_tun_fd"), covered)
    }
}
