package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.CallTransportPath
import com.poyka.ripdpi.core.detection.CallTransportStunScope
import com.poyka.ripdpi.core.detection.CallTransportStunServer
import com.poyka.ripdpi.core.detection.probe.ProxyEndpoint
import com.poyka.ripdpi.core.detection.probe.ProxyType
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

class StunTransportPeerTest {
    private val dispatchers = AppCoroutineDispatchers(Dispatchers.IO, Dispatchers.IO, Dispatchers.IO)
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test
    fun callStunIgnoresAnotherPeerAndAnUnrelatedTransaction() =
        runTest {
            DatagramSocket(InetSocketAddress(loopback, 0)).use { server ->
                DatagramSocket(InetSocketAddress(loopback, 0)).use { otherPeer ->
                    server.soTimeout = 3_000
                    val response =
                        async(Dispatchers.IO) {
                            val packet = receive(server)
                            val transaction = packet.data.copyOfRange(8, 20)
                            val wrongPeerResponse = bindingResponse(transaction, byteArrayOf(192.toByte(), 0, 2, 99))
                            otherPeer.send(
                                DatagramPacket(wrongPeerResponse, wrongPeerResponse.size, packet.socketAddress),
                            )
                            val unrelated = bindingResponse(ByteArray(12), byteArrayOf(192.toByte(), 0, 2, 98))
                            server.send(DatagramPacket(unrelated, unrelated.size, packet.socketAddress))
                            val valid = bindingResponse(transaction)
                            server.send(DatagramPacket(valid, valid.size, packet.socketAddress))
                        }
                    val result =
                        CallTransportLeakChecker.defaultStunClient(dispatchers).reflexiveAddress(
                            CallTransportPath.VPN,
                            CallTransportStunServer("127.0.0.1", server.localPort, CallTransportStunScope.GLOBAL),
                        )
                    response.await()
                    assertEquals("192.0.2.1", result)
                }
            }
        }

    @Test
    fun callStunRejectsAnOversizedDatagramWithAValidMaximumSizePrefix() =
        runTest {
            DatagramSocket(InetSocketAddress(loopback, 0)).use { server ->
                server.soTimeout = 3_000
                val response =
                    async(Dispatchers.IO) {
                        val packet = receive(server)
                        val transaction = packet.data.copyOfRange(8, 20)
                        val oversized = bindingResponse(transaction, byteArrayOf(192.toByte(), 0, 2, 99)).copyOf(1_501)
                        ByteBuffer
                            .wrap(oversized)
                            .putShort(2, 1_480)
                            .putShort(32, 0x8022.toShort())
                            .putShort(34, 1_464)
                        server.send(DatagramPacket(oversized, oversized.size, packet.socketAddress))
                        val valid = bindingResponse(transaction)
                        server.send(DatagramPacket(valid, valid.size, packet.socketAddress))
                    }
                val result =
                    CallTransportLeakChecker.defaultStunClient(dispatchers).reflexiveAddress(
                        CallTransportPath.VPN,
                        CallTransportStunServer("127.0.0.1", server.localPort, CallTransportStunScope.GLOBAL),
                    )
                response.await()
                assertEquals("192.0.2.1", result)
            }
        }

    @Test
    fun socksStunIgnoresInvalidEnvelopesBeforeTheMatchingResponse() =
        runTest {
            DatagramSocket(InetSocketAddress(loopback, 0)).use { relay ->
                ServerSocket(0, 1, loopback).use { proxy ->
                    relay.soTimeout = 3_000
                    proxy.soTimeout = 3_000
                    val server =
                        async(Dispatchers.IO) {
                            proxy.accept().use { control ->
                                val input = DataInputStream(control.getInputStream())
                                input.readFully(ByteArray(3))
                                control.getOutputStream().write(byteArrayOf(5, 0))
                                input.readFully(ByteArray(10))
                                control.getOutputStream().write(
                                    byteArrayOf(5, 0, 0, 1) + loopback.address + port(relay.localPort),
                                )
                                val packet = receive(relay)
                                val headerSize = 7 + packet.data[4].toInt()
                                val transaction = packet.data.copyOfRange(headerSize + 8, headerSize + 20)
                                val valid = envelope(bindingResponse(transaction))
                                val invalidBase =
                                    envelope(bindingResponse(transaction, byteArrayOf(192.toByte(), 0, 2, 99)))
                                val invalid =
                                    listOf(
                                        invalidBase.copyOf().also { it[0] = 1 },
                                        invalidBase.copyOf().also { it[1] = 1 },
                                        invalidBase.copyOf().also { it[2] = 1 },
                                        invalidBase.copyOf().also {
                                            it[8] = 0
                                            it[9] = 1
                                        },
                                        byteArrayOf(0, 0, 0, 3),
                                        byteArrayOf(0, 0, 0, 3, 1, 'x'.code.toByte()) + port(19_302) +
                                            bindingResponse(transaction),
                                    )
                                invalid.forEach { bytes ->
                                    relay.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                                }
                                relay.send(DatagramPacket(valid, valid.size, packet.socketAddress))
                            }
                        }
                    val result =
                        Socks5UdpAssociateStunClient(dispatchers).reflexiveAddress(
                            ProxyEndpoint("127.0.0.1", proxy.localPort, ProxyType.SOCKS5),
                        )
                    server.await()
                    assertEquals("192.0.2.1", result)
                }
            }
        }

    @Test
    fun socksStunRejectsNonzeroControlReservedByte() =
        runTest {
            DatagramSocket(InetSocketAddress(loopback, 0)).use { relay ->
                ServerSocket(0, 1, loopback).use { proxy ->
                    proxy.soTimeout = 3_000
                    relay.soTimeout = 250
                    val server =
                        async(Dispatchers.IO) {
                            proxy.accept().use { control ->
                                val input = DataInputStream(control.getInputStream())
                                input.readFully(ByteArray(3))
                                control.getOutputStream().write(byteArrayOf(5, 0))
                                input.readFully(ByteArray(10))
                                control.getOutputStream().write(
                                    byteArrayOf(5, 0, 1, 1) + loopback.address + port(relay.localPort),
                                )
                                try {
                                    val packet = receive(relay)
                                    val headerSize = 7 + packet.data[4].toInt()
                                    val transaction = packet.data.copyOfRange(headerSize + 8, headerSize + 20)
                                    val reply = envelope(bindingResponse(transaction))
                                    relay.send(DatagramPacket(reply, reply.size, packet.socketAddress))
                                } catch (_: SocketTimeoutException) {
                                    // A rejected control reply must not start a UDP exchange.
                                }
                            }
                        }
                    val result =
                        Socks5UdpAssociateStunClient(dispatchers).reflexiveAddress(
                            ProxyEndpoint("127.0.0.1", proxy.localPort, ProxyType.SOCKS5),
                        )
                    server.await()
                    assertNull(result)
                }
            }
        }

    private fun receive(socket: DatagramSocket): DatagramPacket =
        DatagramPacket(ByteArray(1_500), 1_500).also(socket::receive)

    private fun envelope(payload: ByteArray): ByteArray =
        byteArrayOf(0, 0, 0, 1, 192.toByte(), 0, 2, 20) + port(19_302) + payload

    private fun bindingResponse(
        transaction: ByteArray,
        address: ByteArray = byteArrayOf(192.toByte(), 0, 2, 1),
    ): ByteArray {
        // RFC 5769's IPv4 address; XOR is an independently fixed cookie mask, not a decoder round trip.
        val cookie = byteArrayOf(0x21, 0x12, 0xa4.toByte(), 0x42)
        val encoded = ByteArray(4) { (address[it].toInt() xor cookie[it].toInt()).toByte() }
        return ByteBuffer
            .allocate(32)
            .putShort(0x0101)
            .putShort(12)
            .putInt(0x2112a442)
            .put(transaction)
            .put(byteArrayOf(0, 0x20, 0, 8, 0, 1, 0xa1.toByte(), 0x47))
            .put(encoded)
            .array()
    }

    private fun port(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())
}
