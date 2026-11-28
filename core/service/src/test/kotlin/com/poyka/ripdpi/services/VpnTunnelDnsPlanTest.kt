package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.DnsModePlainUdp
import com.poyka.ripdpi.data.activeDnsSettings
import com.poyka.ripdpi.data.canonicalDefaultEncryptedDnsSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class VpnTunnelDnsPlanTest {
    private val plain =
        AppSettingsSerializer.defaultValue
            .toBuilder()
            .setDnsMode(DnsModePlainUdp)
            .setDnsIp("8.8.8.8")
            .build()
            .activeDnsSettings()
    private val encrypted = canonicalDefaultEncryptedDnsSettings()

    private fun needsTcp(
        dns: ActiveDnsSettings,
        forceTunnelDns: Boolean = false,
    ) = vpnTunnelNeedsTcpProxyListener(dns, forceTunnelDns, splitStrictPolicy = null)

    @Test
    fun `only encrypted dns that rides the proxy needs the tcp listener`() {
        assertEquals(false, needsTcp(plain))
        assertEquals(false, needsTcp(plain.copy(routeThroughProxy = true)))
        assertEquals(false, needsTcp(encrypted))
        assertEquals(true, needsTcp(encrypted.copy(routeThroughProxy = true)))
    }

    @Test
    fun `forced tunnel dns resolves through encrypted dns over the proxy`() {
        assertEquals(true, needsTcp(plain, forceTunnelDns = true))
        assertEquals(true, needsTcp(encrypted, forceTunnelDns = true))
    }
}
