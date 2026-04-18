package com.poyka.ripdpi.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnUpstreamNetworkBindingTest {
    @Test
    fun `binding audit keeps active network when permission and network are available`() {
        val audit =
            resolveVpnUpstreamNetworkBindingAudit(
                hasNetworkStatePermission = true,
                activeNetworkAvailable = true,
            )

        assertTrue(audit.readsActiveNetwork)
        assertTrue(audit.bindsActiveNetwork)
        assertTrue(audit.usesSetUnderlyingNetworks)
        assertFalse(audit.usesBindSocket)
        assertFalse(audit.usesAllowBypass)
    }

    @Test
    fun `binding audit clears underlying networks when network state permission is unavailable`() {
        val audit =
            resolveVpnUpstreamNetworkBindingAudit(
                hasNetworkStatePermission = false,
                activeNetworkAvailable = true,
            )

        assertFalse(audit.readsActiveNetwork)
        assertFalse(audit.bindsActiveNetwork)
        assertEquals(
            "read_active_network=false, active_network_available=true, set_underlying_networks=true, " +
                "bind_socket=false, allow_bypass=false, binds_active_network=false",
            audit.logSummary(),
        )
    }
}
