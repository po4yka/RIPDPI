package com.poyka.ripdpi.core.detection.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReflectedIpAddressTest {
    @Test
    fun `local multicast and tunnel addresses cannot become public ip evidence`() {
        val rejected =
            listOf(
                "0.0.0.0",
                "0.1.2.3",
                "127.0.0.1",
                "10.0.0.1",
                "172.16.0.1",
                "192.168.1.1",
                "169.254.1.1",
                "100.64.0.1",
                "198.18.0.53",
                "224.0.0.1",
                "255.255.255.255",
                "::",
                "::1",
                "::ffff:127.0.0.1",
                "::ffff:192.168.1.1",
                "fc00::1",
                "fd12::1",
                "fe80::1",
                "ff02::1",
                "example.invalid",
                "8.8.8.8 example",
            )
        rejected.forEach { value -> assertNull(value, publicIpOrNull(value)) }
        assertNotNull(publicIpOrNull("8.8.8.8"))
        assertNotNull(publicIpOrNull("2001:4860:4860::8888"))
    }

    @Test
    fun `canonical identity accepts numeric literals only`() {
        assertEquals(canonicalIpOrNull("2001:4860:4860::8888"), canonicalIpOrNull("[2001:4860:4860:0:0:0:0:8888]"))
        assertNull(canonicalIpOrNull("example.invalid"))
        assertNull(canonicalIpOrNull("8.8.8.8/32"))
        assertNull(canonicalIpOrNull("2001:4860::1%en0"))
    }
}
