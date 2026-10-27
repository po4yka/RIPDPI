package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.probe.ProxyEndpoint
import com.poyka.ripdpi.core.detection.probe.ProxyType
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class BypassProxyProbesIpv6Test {
    private val dispatchers =
        AppCoroutineDispatchers(io = Dispatchers.IO, default = Dispatchers.IO, main = Dispatchers.IO)
    private val loopbackV6: InetAddress = InetAddress.getByName("::1")

    @Test
    fun mtProtoConnectAcceptsAnIpv6BoundAddressInTheReply() {
        ServerSocket(0, 1, loopbackV6).use { server ->
            val proxy =
                thread {
                    server.accept().use { client ->
                        val input = DataInputStream(client.getInputStream())
                        input.readFully(ByteArray(3))
                        client.getOutputStream().write(byteArrayOf(5, 0))
                        input.readFully(ByteArray(10))
                        client.getOutputStream().write(
                            byteArrayOf(5, 0, 0, 4) + loopbackV6.address + byteArrayOf(1, -69),
                        )
                        client.getOutputStream().flush()
                        input.read()
                    }
                }

            val reachable =
                runBlocking {
                    Socks5MtProtoProber(dispatchers).canReach(ProxyEndpoint("::1", server.localPort, ProxyType.SOCKS5))
                }
            proxy.join(5_000)

            assertTrue(reachable)
        }
    }

    @Test
    fun stunViaSocks5ReadsAnIpv6RelayAndAnIpv6XorMappedAddress() {
        val reflexive = InetAddress.getByName("2001:db8::7")
        DatagramSocket(InetSocketAddress(loopbackV6, 0)).use { relay ->
            ServerSocket(0, 1, loopbackV6).use { server ->
                val proxy =
                    thread {
                        server.accept().use { client ->
                            val input = DataInputStream(client.getInputStream())
                            input.readFully(ByteArray(3))
                            client.getOutputStream().write(byteArrayOf(5, 0))
                            input.readFully(ByteArray(10))
                            client.getOutputStream().write(
                                byteArrayOf(5, 0, 0, 4) + ByteArray(16) + relay.localPort.toPortBytes(),
                            )
                            client.getOutputStream().flush()
                            answerStun(relay, reflexive)
                            input.read()
                        }
                    }

                val address =
                    runBlocking {
                        Socks5UdpAssociateStunClient(dispatchers)
                            .reflexiveAddress(ProxyEndpoint("::1", server.localPort, ProxyType.SOCKS5))
                    }
                proxy.join(5_000)

                assertEquals(reflexive.hostAddress, address)
            }
        }
    }

    // Replies through the SOCKS5 UDP relay with an IPv6 relay header, a legacy MAPPED-ADDRESS first
    // and the XOR-MAPPED-ADDRESS second, which must win.
    private fun answerStun(
        relay: DatagramSocket,
        reflexive: InetAddress,
    ) {
        val buffer = ByteArray(1_500)
        val request = DatagramPacket(buffer, buffer.size)
        relay.receive(request)
        val domainLength = buffer[4].toInt()
        val stunStart = 4 + 1 + domainLength + 2
        val transactionId = buffer.copyOfRange(stunStart + 8, stunStart + 20)
        val key =
            ByteBuffer
                .allocate(16)
                .putInt(0x2112A442)
                .put(transactionId)
                .array()
        val xorAddress = ByteArray(16) { (reflexive.address[it].toInt() xor key[it].toInt()).toByte() }
        val mapped = byteArrayOf(0, 1, 0, 8, 0, 1, 0, 80, 10, 0, 0, 1)
        val xorMapped = byteArrayOf(0, 0x20, 0, 20, 0, 2, 0x21, 0x12) + xorAddress
        val attributes = mapped + xorMapped
        val stun =
            ByteBuffer
                .allocate(20 + attributes.size)
                .putShort(0x0101)
                .putShort(attributes.size.toShort())
                .putInt(0x2112A442)
                .put(transactionId)
                .put(attributes)
                .array()
        val reply = byteArrayOf(0, 0, 0, 4) + ByteArray(16) + 19_302.toPortBytes() + stun
        relay.send(DatagramPacket(reply, reply.size, request.socketAddress))
    }

    private fun Int.toPortBytes(): ByteArray = byteArrayOf((this shr 8).toByte(), this.toByte())
}
