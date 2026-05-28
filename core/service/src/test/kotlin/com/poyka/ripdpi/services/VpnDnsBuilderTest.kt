package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.DnsModeEncrypted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD: VPN builder always sets the interceptor DNS server for secure profiles
 * and never leaves DNS unset.
 */
class VpnDnsBuilderTest {
    @Test
    fun `encrypted profile returns interceptor address`() {
        val result =
            vpnDnsAddressForProfile(
                dnsMode = DnsModeEncrypted,
                plainDnsIp = "1.1.1.1",
            )
        assertEquals(VpnInterceptorDnsAddress, result)
    }

    @Test
    fun `non-encrypted profile returns configured plain dns ip`() {
        val result =
            vpnDnsAddressForProfile(
                dnsMode = "system",
                plainDnsIp = "8.8.8.8",
            )
        assertEquals("8.8.8.8", result)
    }

    @Test
    fun `relay routed dns returns interceptor address even for plain profile`() {
        val result =
            vpnDnsAddressForProfile(
                dnsMode = "plain_udp",
                plainDnsIp = "8.8.8.8",
                forceTunnelDns = true,
            )
        assertEquals(VpnInterceptorDnsAddress, result)
    }

    @Test
    fun `encrypted profile dns address is always non-blank`() {
        val result =
            vpnDnsAddressForProfile(
                dnsMode = DnsModeEncrypted,
                plainDnsIp = "",
            )
        assertTrue("DNS address must not be blank for encrypted profiles", result.isNotBlank())
    }

    @Test
    fun `interceptor address is a valid private IP not routable to public internet`() {
        // 198.18.0.0/15 is TEST-NET used for benchmarking — safe to use internally
        assertTrue(VpnInterceptorDnsAddress.startsWith("198.18."))
    }

    @Test
    fun `isSecureVpnDnsProfile returns true for encrypted mode`() {
        assertTrue(isSecureVpnDnsProfile(DnsModeEncrypted))
    }

    @Test
    fun `isSecureVpnDnsProfile returns false for system mode`() {
        assertFalse(isSecureVpnDnsProfile("system"))
    }

    @Test
    fun `isSecureVpnDnsProfile returns true when relay routes dns over tunnel`() {
        assertTrue(isSecureVpnDnsProfile("plain_udp", forceTunnelDns = true))
    }

    @Test
    fun `isSecureVpnDnsProfile returns false for empty mode`() {
        assertFalse(isSecureVpnDnsProfile(""))
    }

    @Test
    fun `encrypted profile never returns plain dns ip`() {
        val plainIp = "192.168.1.1"
        val result =
            vpnDnsAddressForProfile(
                dnsMode = DnsModeEncrypted,
                plainDnsIp = plainIp,
            )
        assertTrue(
            "Encrypted profile must not expose plain DNS IP $plainIp to the VPN builder",
            result != plainIp,
        )
    }
}
