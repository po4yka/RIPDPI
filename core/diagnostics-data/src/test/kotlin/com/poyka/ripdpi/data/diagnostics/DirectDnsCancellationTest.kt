package com.poyka.ripdpi.data.diagnostics

import android.net.Network
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNetwork
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DirectDnsCancellationTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test fun `cancel before enqueue reports failure without a datagram`() {
        DatagramSocket(0, loopback).use { udp ->
            val outcome = Outcome()
            val call = resolver(udp.localPort).newCall(Dns.Request("example.com"))
            call.cancel()
            call.enqueue(outcome)
            outcome.assertCancelled()
            assertNoQuery(udp)
        }
    }

    @Test fun `cancelling one call preserves another live lookup`() {
        DatagramSocket(0, loopback).use { udp ->
            udp.soTimeout = 2_000
            val dns = DirectDns(listOf("127.0.0.1"), DetectionAddressFamily.IPV4, udp.localPort, 1_200)
            val cancelled = Outcome()
            val completed = Outcome()
            val first = dns.newCall(Dns.Request("first.example"))
            first.enqueue(cancelled)
            receive(udp)
            dns.newCall(Dns.Request("second.example")).enqueue(completed)
            val second = receive(udp)
            first.cancel()
            val answer =
                second.data.copyOf(second.length).also {
                    it[2] = 0x81.toByte()
                    it[3] = 0x80.toByte()
                    it[7] = 1
                } + "c00c000100010000003c000408080808".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            udp.send(DatagramPacket(answer, answer.size, second.socketAddress))
            cancelled.assertCancelled()
            completed.assertResolved()
        }
    }

    @Test
    @Config(shadows = [DnsSocketNetworkShadow::class])
    fun `cancel closes the actual UDP socket and prevents retries`() {
        DatagramSocket(0, loopback).use { udp ->
            udp.soTimeout = 2_000
            val outcome = Outcome()
            val network = ShadowNetwork.newInstance(100)
            val call =
                resolver(udp.localPort, DetectionNetworkBinding.AndroidNetworkBinding(network))
                    .newCall(Dns.Request("example.com"))
            call.enqueue(outcome)
            val query = receive(udp)
            val client = requireNotNull(Shadow.extract<DnsSocketNetworkShadow>(network).socket)
            assertFalse(client.isClosed)
            assertEquals(query.port, client.localPort)
            call.cancel()
            assertTrue(client.isClosed)
            outcome.assertCancelled()
            assertTrue(call.isCanceled())
            assertNoQuery(udp)
        }
    }

    @Test fun `cancel closes stalled TCP fallback and prevents retries`() {
        ServerSocket(0, 1, loopback).use { tcp ->
            tcp.soTimeout = 2_000
            DatagramSocket(tcp.localPort, loopback).use { udp ->
                udp.soTimeout = 2_000
                val outcome = Outcome()
                val call = resolver(tcp.localPort).newCall(Dns.Request("example.com"))
                call.enqueue(outcome)
                val query = receive(udp)
                val truncated =
                    query.data.copyOf(query.length).also {
                        it[2] = 0x83.toByte()
                        it[3] = 0x80.toByte()
                    }
                udp.send(DatagramPacket(truncated, truncated.size, query.socketAddress))
                tcp.accept().use { peer ->
                    peer.soTimeout = 1_000
                    val input = DataInputStream(peer.getInputStream())
                    val frame = ByteArray(input.readUnsignedShort()).also(input::readFully)
                    assertTrue(frame.contentEquals(query.data.copyOf(query.length)))
                    call.cancel()
                    assertEquals(-1, input.read())
                    outcome.assertCancelled()
                    assertNoQuery(udp)
                }
            }
        }
    }

    private fun resolver(
        port: Int,
        binding: DetectionNetworkBinding = DetectionNetworkBinding.None,
    ) = DirectDns(
        listOf("127.0.0.1", "127.0.0.1"),
        DetectionAddressFamily.ANY,
        port,
        5_000,
        binding,
    )

    private fun receive(socket: DatagramSocket): DatagramPacket =
        DatagramPacket(ByteArray(1024), 1024).also(socket::receive)

    private fun assertNoQuery(socket: DatagramSocket) {
        socket.soTimeout = 250
        assertThrows(SocketTimeoutException::class.java) { receive(socket) }
    }

    private class Outcome : Dns.Callback {
        private val terminal = CountDownLatch(1)
        private val failures = AtomicInteger()
        private val successes = AtomicInteger()
        private val addresses = AtomicReference<List<String>>()

        override fun onRecords(
            call: Dns.Call,
            last: Boolean,
            records: List<Dns.Record>,
        ) {
            addresses.set(records.filterIsInstance<Dns.Record.IpAddress>().mapNotNull { it.address.hostAddress })
            if (last) {
                successes.incrementAndGet()
                terminal.countDown()
            }
        }

        override fun onFailure(
            call: Dns.Call,
            e: IOException,
        ) {
            failures.incrementAndGet()
            terminal.countDown()
        }

        fun assertCancelled() {
            assertTrue(terminal.await(500, TimeUnit.MILLISECONDS))
            assertEquals(1, failures.get())
            assertEquals(0, successes.get())
        }

        fun assertResolved() {
            assertTrue(terminal.await(500, TimeUnit.MILLISECONDS))
            assertEquals(listOf("8.8.8.8"), addresses.get())
            assertEquals(1, successes.get())
            assertEquals(0, failures.get())
        }
    }
}

@Implements(Network::class)
class DnsSocketNetworkShadow : ShadowNetwork() {
    @Volatile
    var socket: DatagramSocket? = null
        private set

    @Implementation
    override fun bindSocket(socket: DatagramSocket) {
        this.socket = socket
    }
}
