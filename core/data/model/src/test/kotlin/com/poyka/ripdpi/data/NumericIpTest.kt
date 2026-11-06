package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address

class NumericIpTest {
    @Test
    fun `rejects malformed and nonstandard literals without hostname resolution`() {
        listOf(
            "",
            " ",
            "localhost",
            "example.com",
            ":1::2",
            "1::2:",
            "1::2::3",
            ":::1",
            "1:2:3:4:5:6:7",
            "1:2:3:4:5:6:7:8:9",
            "fe80::1%wlan0",
            "[::1]",
            "127.1",
            "2130706433",
            "01.2.3.4",
            "0x7f.0.0.1",
            "256.0.0.1",
            "1.2.3.-1",
            "1.2.3.*",
            "1-2.3.4.5",
            "１.2.3.4",
            " 192.0.2.1",
            "::1\n",
            "::/0",
            "::ffff:192.0.02.1",
            "1.2.3.4/255.255.255.0",
        ).forEach { assertNull(it, NumericIp.parseAddress(it)) }
    }

    @Test
    fun `accepts IPv4 IPv6 and mixed IPv6 without losing family`() {
        assertTrue(requireNotNull(NumericIp.parseAddress("192.0.2.1")).isIPv4)
        listOf("::", "::1", "2001:DB8::1", "0001:0002:0003:0004:0005:0006:0007:0008").forEach {
            assertTrue(it, requireNotNull(NumericIp.parseAddress(it)).isIPv6)
        }
        val mapped = requireNotNull(NumericIp.parseAddress("::ffff:192.0.2.1"))
        assertTrue(mapped.isIPv6)
        assertTrue(mapped.toInetAddress() is Inet6Address)
        assertEquals("0:0:0:0:0:ffff:c000:201", mapped.toNormalizedString())
    }

    @Test
    fun `CIDR keeps interface host and canonicalizes network separately`() {
        val ipv4 = requireNotNull(NumericIp.parseCidr("192.0.2.129/24"))
        assertEquals("192.0.2.129", ipv4.address.toNormalizedString())
        assertEquals("192.0.2.0/24", ipv4.toCanonicalString())
        val ipv6 = requireNotNull(NumericIp.parseCidr("2001:DB8::1234/64"))
        assertEquals("2001:db8:0:0:0:0:0:1234", ipv6.address.toNormalizedString())
        assertEquals("2001:db8:0:0:0:0:0:0/64", ipv6.toCanonicalString())
        assertEquals("0:0:0:0:0:ffff:c000:200/120", NumericIp.canonicalCidr("::ffff:192.0.2.129/120"))
    }

    @Test
    fun `rejects invalid CIDR prefix grammar and widths`() {
        listOf("", "/", "/+1", "/-1", "/01", "/ 1", "/1/2", "/33", "/9999999999", "/255.0.0.0").forEach {
            assertNull(it, NumericIp.parseCidr("192.0.2.1$it"))
        }
        listOf("::1/129", "::1/064", "::1/+64", "::1/-1", "example.com/24", "01.2.3.4/24").forEach {
            assertNull(it, NumericIp.parseCidr(it))
        }
    }

    @Test
    fun `contains respects boundaries and address families`() {
        val allV4 = requireNotNull(NumericIp.parseCidr("192.0.2.1/0"))
        assertTrue(allV4.contains(requireNotNull(NumericIp.parseAddress("255.255.255.255"))))
        assertFalse(allV4.contains(requireNotNull(NumericIp.parseAddress("::ffff:192.0.2.1"))))
        val allV6 = requireNotNull(NumericIp.parseCidr("2001:db8::1/0"))
        assertTrue(allV6.contains(requireNotNull(NumericIp.parseAddress("ffff::1"))))
        assertFalse(allV6.contains(requireNotNull(NumericIp.parseAddress("192.0.2.1"))))
        listOf("192.0.2.1/32" to "192.0.2.2", "2001:db8::1/128" to "2001:db8::2").forEach { (cidr, other) ->
            val prefix = requireNotNull(NumericIp.parseCidr(cidr))
            assertTrue(prefix.contains(prefix.address))
            assertFalse(prefix.contains(requireNotNull(NumericIp.parseAddress(other))))
        }
        assertEquals("0.0.0.0/0", allV4.toCanonicalString())
        assertEquals("0:0:0:0:0:0:0:0/0", allV6.toCanonicalString())
    }
}
