package com.poyka.ripdpi.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LocalNetworkAccessTest {
    @Test
    fun `direct IPv6 unique local destination requires LAN permission`() {
        assertTrue(LocalNetworkAddressPolicy.requiresPermission(InetAddress.getByName("fd12:3456::1")))
    }

    @Test
    fun `loopback and public peers remain available without LAN permission`() {
        listOf("127.0.0.1", "127.255.0.1", "::1", "8.8.8.8", "2606:4700:4700::1111").forEach {
            assertFalse(it, LocalNetworkAddressPolicy.requiresPermission(InetAddress.getByName(it)))
        }
    }

    @Test
    fun `private link local multicast and wildcard listeners require LAN permission`() {
        listOf(
            "10.0.0.1",
            "172.16.0.1",
            "192.168.1.1",
            "169.254.1.1",
            "fe80::1",
            "fc00::1",
            "224.0.0.251",
            "ff02::fb",
            "255.255.255.255",
            "0.0.0.0",
            "::",
        ).forEach {
            assertTrue(it, LocalNetworkAddressPolicy.requiresPermission(InetAddress.getByName(it)))
        }
    }
}
