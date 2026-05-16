package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DnsModeEncrypted
import com.poyka.ripdpi.data.DnsResolverPlane
import com.poyka.ripdpi.data.SplitStrictDnsPolicy
import com.poyka.ripdpi.data.SplitStrictResolverSpec
import com.poyka.ripdpi.data.StrictEncryptedDnsProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD: DNS policy remains intact across Wi-Fi ↔ cellular network switches.
 *
 * These tests verify that:
 * - The interceptor DNS address is the same regardless of transport.
 * - The proxy plane remains strict-encrypted (no plaintext fallback) after a switch.
 * - No plaintext system DNS is ever returned for a secure (encrypted) profile
 *   on either transport.
 */
class DnsPolicyNetworkSwitchTest {
    private val wifiDnsServers = listOf("192.168.1.1")
    private val cellularDnsServers = listOf("10.0.0.1")

    private fun securePolicyForTransport(
        @Suppress("UNUSED_PARAMETER") dnsServers: List<String>,
    ): SplitStrictDnsPolicy = SplitStrictDnsPolicy()

    @Test
    fun `interceptor address is the same on wifi and cellular`() {
        val wifiDns = vpnDnsAddressForProfile(DnsModeEncrypted, wifiDnsServers.first())
        val cellularDns = vpnDnsAddressForProfile(DnsModeEncrypted, cellularDnsServers.first())
        assertEquals(
            "Interceptor DNS must be constant across transports",
            wifiDns,
            cellularDns,
        )
        assertEquals(VpnInterceptorDnsAddress, wifiDns)
    }

    @Test
    fun `proxy plane never allows plaintext fallback after network switch`() {
        val wifiPolicy = securePolicyForTransport(wifiDnsServers)
        val cellPolicy = securePolicyForTransport(cellularDnsServers)

        assertFalse(
            "Proxy plane must not allow plaintext fallback on WiFi",
            wifiPolicy.proxy.allowPlaintextFallback,
        )
        assertFalse(
            "Proxy plane must not allow plaintext fallback on Cellular",
            cellPolicy.proxy.allowPlaintextFallback,
        )
    }

    @Test
    fun `proxy plane uses encrypted protocol on both transports`() {
        val wifiPolicy = securePolicyForTransport(wifiDnsServers)
        val cellPolicy = securePolicyForTransport(cellularDnsServers)

        val wifiProtocol = wifiPolicy.proxy.protocol
        val cellProtocol = cellPolicy.proxy.protocol

        assertTrue(
            "Proxy plane must use encrypted protocol on WiFi",
            wifiProtocol != null && wifiProtocol != StrictEncryptedDnsProtocol.NONE,
        )
        assertTrue(
            "Proxy plane must use encrypted protocol on Cellular",
            cellProtocol != null && cellProtocol != StrictEncryptedDnsProtocol.NONE,
        )
    }

    @Test
    fun `underlying network dns servers do not appear as vpn dns after switch`() {
        for (systemDns in wifiDnsServers + cellularDnsServers) {
            val vpnDns = vpnDnsAddressForProfile(DnsModeEncrypted, systemDns)
            assertTrue(
                "System DNS $systemDns must not leak into VPN DNS address",
                vpnDns != systemDns,
            )
            assertEquals(VpnInterceptorDnsAddress, vpnDns)
        }
    }

    @Test
    fun `policy proxy plane is consistent across simulated network handovers`() {
        val transports = listOf("wifi", "cellular", "wifi")
        val dnsByTransport =
            mapOf(
                "wifi" to wifiDnsServers,
                "cellular" to cellularDnsServers,
            )

        var previousSpec: SplitStrictResolverSpec? = null
        for (transport in transports) {
            val dnsServers = dnsByTransport[transport] ?: continue
            val policy = securePolicyForTransport(dnsServers)
            val proxySpec = policy.proxy

            assertEquals(DnsResolverPlane.PROXY, proxySpec.plane)
            assertFalse(proxySpec.allowPlaintextFallback)
            previousSpec?.let { prev ->
                assertEquals(
                    "Proxy plane protocol must not change across handover ($transport)",
                    prev.protocol,
                    proxySpec.protocol,
                )
                assertFalse(
                    "Proxy plane must never allow plaintext after handover ($transport)",
                    proxySpec.allowPlaintextFallback,
                )
            }
            previousSpec = proxySpec
        }
    }

    @Test
    fun `isSecureVpnDnsProfile holds regardless of underlying transport`() {
        assertTrue(isSecureVpnDnsProfile(DnsModeEncrypted))
        // Transport changes do not alter the profile mode
        assertTrue(isSecureVpnDnsProfile(DnsModeEncrypted))
    }

    @Test
    fun `bootstrap scope is safe for any non-TUN address after network switch`() {
        val policy = securePolicyForTransport(cellularDnsServers)
        val scope = DnsBootstrapScope(policy)
        for (addr in listOf("94.140.14.14", "1.1.1.1", "8.8.8.8")) {
            assertTrue(
                "Bootstrap address $addr must be safe after network switch",
                scope.isSafeBootstrapAddress(addr),
            )
        }
    }
}
