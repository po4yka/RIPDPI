package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.proto.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRelayDnsPolicyTest {
    @Test
    fun `relay active defaults dns into the tunnel`() {
        val settings =
            AppSettings
                .newBuilder()
                .setRelayEnabled(true)
                .setRelayKind(RelayKindVlessReality)
                .build()

        assertTrue(forceTunnelDnsForRelay(settings))
    }

    @Test
    fun `explicit relay dns opt out disables forced tunnel dns`() {
        val settings =
            AppSettings
                .newBuilder()
                .setRelayEnabled(true)
                .setRelayKind(RelayKindVlessReality)
                .setRelayDnsOverTunnelEnabled(false)
                .build()

        assertFalse(forceTunnelDnsForRelay(settings))
    }

    @Test
    fun `disabled relay does not force dns into the tunnel`() {
        val settings =
            AppSettings
                .newBuilder()
                .setRelayEnabled(false)
                .setRelayKind(RelayKindVlessReality)
                .build()

        assertFalse(forceTunnelDnsForRelay(settings))
    }
}
