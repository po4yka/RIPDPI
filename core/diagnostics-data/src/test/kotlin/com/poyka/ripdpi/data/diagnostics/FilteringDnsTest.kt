package com.poyka.ripdpi.data.diagnostics

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

class FilteringDnsTest {
    @Test
    fun `ipv4 mode drops ipv6 answers`() {
        val dns =
            FilteringDns(
                delegate = StaticDns("192.0.2.1", "2001:db8::1"),
                addressFamily = DetectionAddressFamily.IPV4,
            )

        val addresses = dns.lookup("example.com")

        assertEquals(1, addresses.size)
        assertEquals(Inet4Address::class, addresses.single()::class)
    }

    @Test
    fun `ipv6 mode drops ipv4 answers`() {
        val dns =
            FilteringDns(
                delegate = StaticDns("192.0.2.1", "2001:db8::1"),
                addressFamily = DetectionAddressFamily.IPV6,
            )

        val addresses = dns.lookup("example.com")

        assertEquals(1, addresses.size)
        assertEquals(Inet6Address::class, addresses.single()::class)
    }

    @Test(expected = UnknownHostException::class)
    fun `filter throws when no answers remain`() {
        FilteringDns(
            delegate = StaticDns("192.0.2.1"),
            addressFamily = DetectionAddressFamily.IPV6,
        ).lookup("example.com")
    }

    private class StaticDns(
        vararg addresses: String,
    ) : Dns {
        private val values = addresses.map(InetAddress::getByName)

        override fun lookup(hostname: String): List<InetAddress> = values
    }
}
