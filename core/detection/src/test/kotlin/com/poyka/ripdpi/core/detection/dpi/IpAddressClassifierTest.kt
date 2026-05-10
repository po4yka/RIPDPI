package com.poyka.ripdpi.core.detection.dpi

import org.junit.Assert.assertEquals
import org.junit.Test

class IpAddressClassifierTest {
    @Test
    fun `198 18 lower boundary is fake ip`() {
        assertEquals(IpAddressType.FAKE_IP, IpAddressClassifier.classify("198.18.0.0"))
    }

    @Test
    fun `198 19 upper boundary is fake ip`() {
        assertEquals(IpAddressType.FAKE_IP, IpAddressClassifier.classify("198.19.255.255"))
    }

    @Test
    fun `198 20 just outside fake ip range is public`() {
        assertEquals(IpAddressType.PUBLIC, IpAddressClassifier.classify("198.20.0.0"))
    }

    @Test
    fun `100 64 lower boundary is isp stub`() {
        assertEquals(IpAddressType.ISP_STUB, IpAddressClassifier.classify("100.64.0.0"))
    }

    @Test
    fun `100 127 upper boundary is isp stub`() {
        assertEquals(IpAddressType.ISP_STUB, IpAddressClassifier.classify("100.127.255.255"))
    }

    @Test
    fun `100 128 just outside isp stub range is public`() {
        assertEquals(IpAddressType.PUBLIC, IpAddressClassifier.classify("100.128.0.0"))
    }

    @Test
    fun `private ipv4 address is local`() {
        assertEquals(IpAddressType.LOCAL, IpAddressClassifier.classify("192.168.1.1"))
    }

    @Test
    fun `loopback ipv4 address is local`() {
        assertEquals(IpAddressType.LOCAL, IpAddressClassifier.classify("127.0.0.1"))
    }

    @Test
    fun `link local ipv4 address is local`() {
        assertEquals(IpAddressType.LOCAL, IpAddressClassifier.classify("169.254.1.10"))
    }

    @Test
    fun `unspecified ipv4 address is local`() {
        assertEquals(IpAddressType.LOCAL, IpAddressClassifier.classify("0.0.0.0"))
    }

    @Test
    fun `link local ipv6 address is local`() {
        assertEquals(IpAddressType.LOCAL, IpAddressClassifier.classify("fe80::1"))
    }

    @Test
    fun `loopback ipv6 address is local`() {
        assertEquals(IpAddressType.LOCAL, IpAddressClassifier.classify("::1"))
    }

    @Test
    fun `public ipv4 address is public`() {
        assertEquals(IpAddressType.PUBLIC, IpAddressClassifier.classify("8.8.8.8"))
    }

    @Test
    fun `public ipv6 address is public`() {
        assertEquals(IpAddressType.PUBLIC, IpAddressClassifier.classify("2001:4860:4860::8888"))
    }

    @Test
    fun `invalid ip string is local fallback`() {
        assertEquals(IpAddressType.LOCAL, IpAddressClassifier.classify("not an ip"))
    }
}
