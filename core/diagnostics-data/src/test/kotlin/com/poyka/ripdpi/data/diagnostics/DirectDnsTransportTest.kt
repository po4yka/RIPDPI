package com.poyka.ripdpi.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DirectDnsTransportTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun `truncated UDP retries the same query on TCP`() =
        withFixture { udp, tcp ->
            val peer = receiveQuery(udp)
            val truncated =
                peer.data.copyOf(peer.length).also {
                    it[2] = 0x83.toByte()
                    it[3] = 0x80.toByte()
                }
            udp.send(DatagramPacket(truncated, truncated.size, peer.socketAddress))
            tcp.accept().use { socket ->
                socket.soTimeout = 2_000
                val input = DataInputStream(socket.getInputStream())
                val query = ByteArray(input.readUnsignedShort()).also(input::readFully)
                assertTrue(query.contentEquals(peer.data.copyOf(peer.length)))
                val response = answer(query)
                DataOutputStream(socket.getOutputStream()).apply {
                    writeShort(response.size)
                    write(response)
                    flush()
                }
            }
        }

    @Test
    fun `large UDP answer is not cut to the old buffer limit`() =
        withFixture { udp, _ ->
            val peer = receiveQuery(udp)
            val query = peer.data.copyOf(peer.length)
            val padding = ByteArray(2_000)
            val response =
                answer(query).also { it[11] = 1 } +
                    hex("00002910000000000007d4000c07d0") + padding
            assertTrue(response.size > 1500)
            udp.send(DatagramPacket(response, response.size, peer.socketAddress))
        }

    @Test
    fun `TCP fallback rejects a response for another question`() =
        withFixture(expectFailure = true) { udp, tcp ->
            val peer = receiveQuery(udp)
            val truncated =
                peer.data.copyOf(peer.length).also {
                    it[2] = 0x83.toByte()
                    it[3] = 0x80.toByte()
                }
            udp.send(DatagramPacket(truncated, truncated.size, peer.socketAddress))
            tcp.accept().use { socket ->
                socket.soTimeout = 2_000
                val input = DataInputStream(socket.getInputStream())
                val query = ByteArray(input.readUnsignedShort()).also(input::readFully)
                val response = answer(query).also { it[13] = 'x'.code.toByte() }
                DataOutputStream(socket.getOutputStream()).apply {
                    writeShort(response.size)
                    write(response)
                    flush()
                }
            }
        }

    @Test
    fun `unrelated UDP question is ignored before matching answer`() =
        withFixture { udp, _ ->
            val peer = receiveQuery(udp)
            val response = answer(peer.data.copyOf(peer.length))
            val unrelated = response.copyOf().also { it[13] = 'x'.code.toByte() }
            udp.send(DatagramPacket(unrelated, unrelated.size, peer.socketAddress))
            udp.send(DatagramPacket(response, response.size, peer.socketAddress))
        }

    private fun withFixture(
        expectFailure: Boolean = false,
        serve: (DatagramSocket, ServerSocket) -> Unit,
    ) {
        ServerSocket(0, 1, loopback).use { tcp ->
            tcp.soTimeout = 2_000
            DatagramSocket(tcp.localPort, loopback).use { udp ->
                udp.soTimeout = 2_000
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val served = executor.submit { serve(udp, tcp) }
                    val resolver = DirectDns(listOf("127.0.0.1"), DetectionAddressFamily.IPV4, tcp.localPort, 2_000)
                    assertResolution(resolver, expectFailure)
                    served.get(3, TimeUnit.SECONDS)
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    }

    private fun assertResolution(
        resolver: DirectDns,
        expectFailure: Boolean,
    ) {
        if (expectFailure) {
            assertThrows(UnknownHostException::class.java) { resolver.lookup("example.com") }
        } else {
            assertEquals(listOf("192.0.2.1"), resolver.lookup("example.com").map { it.hostAddress })
        }
    }

    private fun receiveQuery(socket: DatagramSocket): DatagramPacket =
        DatagramPacket(ByteArray(1024), 1024).also(socket::receive)

    private fun answer(query: ByteArray): ByteArray =
        query.copyOf().also {
            it[2] = 0x81.toByte()
            it[3] = 0x80.toByte()
            it[7] = 1
        } +
            hex("c00c000100010000003c0004c0000201")

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
