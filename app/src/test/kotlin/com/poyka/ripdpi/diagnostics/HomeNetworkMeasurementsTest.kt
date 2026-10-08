package com.poyka.ripdpi.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException

class HomeNetworkMeasurementsTest {
    @Test
    fun `IPv6 reachability requires bounded IPv6 connect and closes socket`() {
        val socket = FakeHomeIpv6Socket()
        assertEquals(true, measureHomeIpv6Reachability({ socket }) { true })
        assertTrue((socket.endpoint as InetSocketAddress).address is Inet6Address)
        assertEquals(HomeIpv6ConnectTimeoutMs, socket.timeout)
        assertTrue(socket.closed)
    }

    @Test
    fun `IPv6 connect timeout is not reachable and closes socket`() {
        val socket = FakeHomeIpv6Socket(timesOut = true)
        assertEquals(false, measureHomeIpv6Reachability({ socket }) { true })
        assertTrue(socket.closed)
    }

    @Test
    fun `missing or changed network returns unknown without opening a socket`() {
        var created = false
        assertNull(measureHomeIpv6Reachability(null) { true })
        val socketFactory = {
            created = true
            FakeHomeIpv6Socket()
        }
        assertNull(measureHomeIpv6Reachability(socketFactory) { false })
        assertFalse(created)
    }

    @Test
    fun `network change during connect discards result and closes captured socket`() {
        var current = true
        val socket = FakeHomeIpv6Socket(onConnect = { current = false })
        assertNull(measureHomeIpv6Reachability({ socket }) { current })
        assertTrue(socket.closed)
    }

    @Test
    fun `resolver identity comes from configured DNS servers or remains unknown`() {
        assertEquals("192.0.2.53", homeSystemResolver(listOf(InetAddress.getByName("192.0.2.53"))))
        assertNull(homeSystemResolver(null))
        assertNull(homeSystemResolver(emptyList()))
    }
}

private class FakeHomeIpv6Socket(
    private val timesOut: Boolean = false,
    private val onConnect: () -> Unit = {},
) : Socket() {
    var endpoint: SocketAddress? = null
    var timeout: Int? = null
    var closed = false

    override fun connect(
        endpoint: SocketAddress?,
        timeout: Int,
    ) {
        this.endpoint = endpoint
        this.timeout = timeout
        onConnect()
        if (timesOut) throw SocketTimeoutException("IPv6 connection timed out")
    }

    override fun close() {
        closed = true
    }
}
