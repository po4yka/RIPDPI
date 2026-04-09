package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.checker.IndirectSignsChecker.DnsClassification
import org.junit.Assert.assertEquals
import org.junit.Test

class IndirectSignsCheckerTest {
    @Test
    fun `loopback IPv4 classified correctly`() {
        assertEquals(DnsClassification.LOOPBACK, IndirectSignsChecker.classifyDnsAddress("127.0.0.1"))
        assertEquals(DnsClassification.LOOPBACK, IndirectSignsChecker.classifyDnsAddress("127.0.0.53"))
    }

    @Test
    fun `loopback IPv6 classified correctly`() {
        assertEquals(DnsClassification.LOOPBACK, IndirectSignsChecker.classifyDnsAddress("::1"))
    }

    @Test
    fun `private LAN 192_168 classified correctly`() {
        assertEquals(DnsClassification.PRIVATE_LAN, IndirectSignsChecker.classifyDnsAddress("192.168.1.1"))
    }

    @Test
    fun `private tunnel 10_x classified correctly`() {
        assertEquals(DnsClassification.PRIVATE_TUNNEL, IndirectSignsChecker.classifyDnsAddress("10.0.0.1"))
    }

    @Test
    fun `private tunnel 172_16 classified correctly`() {
        assertEquals(DnsClassification.PRIVATE_TUNNEL, IndirectSignsChecker.classifyDnsAddress("172.16.0.1"))
        assertEquals(DnsClassification.PRIVATE_TUNNEL, IndirectSignsChecker.classifyDnsAddress("172.31.255.1"))
    }

    @Test
    fun `172 outside private range is other public`() {
        assertEquals(DnsClassification.OTHER_PUBLIC, IndirectSignsChecker.classifyDnsAddress("172.32.0.1"))
    }

    @Test
    fun `known public resolver classified correctly`() {
        assertEquals(DnsClassification.KNOWN_PUBLIC_RESOLVER, IndirectSignsChecker.classifyDnsAddress("8.8.8.8"))
        assertEquals(DnsClassification.KNOWN_PUBLIC_RESOLVER, IndirectSignsChecker.classifyDnsAddress("1.1.1.1"))
        assertEquals(DnsClassification.KNOWN_PUBLIC_RESOLVER, IndirectSignsChecker.classifyDnsAddress("9.9.9.9"))
    }

    @Test
    fun `link-local IPv4 classified correctly`() {
        assertEquals(DnsClassification.LINK_LOCAL, IndirectSignsChecker.classifyDnsAddress("169.254.1.1"))
    }

    @Test
    fun `link-local IPv6 classified correctly`() {
        assertEquals(DnsClassification.LINK_LOCAL, IndirectSignsChecker.classifyDnsAddress("fe80::1"))
    }

    @Test
    fun `FC ULA IPv6 classified as private tunnel`() {
        assertEquals(DnsClassification.PRIVATE_TUNNEL, IndirectSignsChecker.classifyDnsAddress("fc00::1"))
        assertEquals(DnsClassification.PRIVATE_TUNNEL, IndirectSignsChecker.classifyDnsAddress("fd12:3456::1"))
    }

    @Test
    fun `regular public IP classified as other`() {
        assertEquals(DnsClassification.OTHER_PUBLIC, IndirectSignsChecker.classifyDnsAddress("203.0.113.1"))
    }
}
