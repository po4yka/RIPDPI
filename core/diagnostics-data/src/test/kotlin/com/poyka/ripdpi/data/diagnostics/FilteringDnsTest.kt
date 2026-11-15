package com.poyka.ripdpi.data.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

    @Test
    fun `async ipv4 mode filters records before resolution`() =
        runTest {
            withContext(Dispatchers.IO) {
                val dns = FilteringDns(StaticDns("192.0.2.1", "2001:db8::1"), DetectionAddressFamily.IPV4)
                assertEquals(listOf("192.0.2.1"), resolveDnsAddresses(dns, "example.com", 1_000).map { it.hostAddress })
            }
        }

    @Test(expected = UnknownHostException::class)
    fun `async mode fails when no addresses remain`() =
        runTest {
            withContext(Dispatchers.IO) {
                val dns = FilteringDns(StaticDns("192.0.2.1"), DetectionAddressFamily.IPV6)
                resolveDnsAddresses(dns, "example.com", 1_000)
            }
        }

    @Test(expected = UnknownHostException::class)
    fun `ipv6 resolver rejects an ipv4 literal`() =
        runTest {
            withContext(Dispatchers.IO) {
                DetectionResolverNetworkStack().resolveAddressesCancellable(
                    "8.8.8.8",
                    DetectionResolverConfig(addressFamily = DetectionAddressFamily.IPV6),
                )
            }
        }

    @Test(expected = UnknownHostException::class)
    fun `ipv4 resolver rejects an ipv6 literal`() =
        runTest {
            withContext(Dispatchers.IO) {
                DetectionResolverNetworkStack().resolveAddressesCancellable(
                    "2001:db8::1",
                    DetectionResolverConfig(addressFamily = DetectionAddressFamily.IPV4),
                )
            }
        }

    private class StaticDns(
        vararg addresses: String,
    ) : Dns {
        private val values = addresses.map(InetAddress::getByName)

        override fun lookup(hostname: String): List<InetAddress> = values
    }
}
