package com.poyka.ripdpi.utility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29])
class ValidateUtilsTest {
    @Test
    fun `accepts numeric IPv4 and IPv6 literals`() {
        assertTrue(checkIp("192.0.2.10"))
        assertTrue(checkIp("2001:db8::10"))
    }

    @Test
    fun `rejects hostnames and malformed IP literals`() {
        assertFalse(checkIp("example.com"))
        assertFalse(checkIp("999.0.2.10"))
        assertFalse(checkIp("0000.0.2.10"))
        assertFalse(checkIp("2001:db8:::10"))
    }

    @Test
    fun `rejects wildcard and loopback DNS addresses`() {
        assertFalse(checkNotLocalIp("0.0.0.0"))
        assertFalse(checkNotLocalIp("127.0.0.1"))
        assertFalse(checkNotLocalIp("::1"))
        assertTrue(checkNotLocalIp("192.0.2.10"))
    }
}
