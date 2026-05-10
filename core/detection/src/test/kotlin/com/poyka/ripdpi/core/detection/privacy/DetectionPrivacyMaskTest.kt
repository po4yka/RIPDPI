package com.poyka.ripdpi.core.detection.privacy

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectionPrivacyMaskTest {
    @Test
    fun `public ipv4 masks last two octets`() {
        assertEquals("1.2.*.*", DetectionPrivacyMask.maskIp("1.2.3.4", enabled = true))
    }

    @Test
    fun `private ipv4 passes through unmasked`() {
        assertEquals("192.168.1.100", DetectionPrivacyMask.maskIp("192.168.1.100", enabled = true))
        assertEquals("10.5.6.7", DetectionPrivacyMask.maskIp("10.5.6.7", enabled = true))
        assertEquals("172.20.1.2", DetectionPrivacyMask.maskIp("172.20.1.2", enabled = true))
    }

    @Test
    fun `loopback passes through unmasked`() {
        assertEquals("127.0.0.1", DetectionPrivacyMask.maskIp("127.0.0.1", enabled = true))
        assertEquals("::1", DetectionPrivacyMask.maskIp("::1", enabled = true))
    }

    @Test
    fun `public ipv6 masks last four groups`() {
        assertEquals(
            "2001:db8:85a3:0:****:****:****:****",
            DetectionPrivacyMask.maskIp("2001:db8:85a3:0:0:8a2e:370:7334", enabled = true),
        )
    }

    @Test
    fun `link local ipv6 passes through`() {
        assertEquals("fe80::1", DetectionPrivacyMask.maskIp("fe80::1", enabled = true))
    }

    @Test
    fun `mask disabled returns original`() {
        assertEquals("1.2.3.4", DetectionPrivacyMask.maskIp("1.2.3.4", enabled = false))
    }

    @Test
    fun `mask ips in text replaces all public occurrences`() {
        val text = "proxy=1.2.3.4 dns=8.8.8.8 local=192.168.1.10 ipv6=2001:db8:85a3:0:0:8a2e:370:7334"

        assertEquals(
            "proxy=1.2.*.* dns=8.8.*.* local=192.168.1.10 ipv6=2001:db8:85a3:0:****:****:****:****",
            DetectionPrivacyMask.maskIpsInText(text, enabled = true),
        )
    }
}
