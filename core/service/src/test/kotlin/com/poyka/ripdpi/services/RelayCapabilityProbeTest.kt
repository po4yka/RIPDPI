package com.poyka.ripdpi.services

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RelayCapabilityProbeTest {
    @Test
    fun `udp requirement rejects relay whose tcp probe succeeds but udp probe fails`() =
        runBlocking {
            val probe =
                RelayCapabilityProbe(
                    tcpProbe = RelayTcpProbe { _, _ -> RelayTcpProbeResult(succeeded = true, statusCode = 204) },
                    udpProbe = RelayUdpAssociateProbe { RelayUdpProbeResult.failure(RelayProbeFailure.UdpReadTimeout) },
                )

            val result =
                probe.probe(
                    endpoint = RelayProbeEndpoint("127.0.0.1", 1080),
                    url = "https://probe.example/generate_204",
                    requirements = EgressRequirements(tcpConnect = true, udpAssociate = true),
                )

            assertFalse(result.succeeded)
            assertEquals(RelayProbeFailure.UdpReadTimeout.wireValue, result.failure)
        }

    @Test
    fun `tcp only requirement does not spend a udp probe`() =
        runBlocking {
            var udpProbeCalls = 0
            val probe =
                RelayCapabilityProbe(
                    tcpProbe = RelayTcpProbe { _, _ -> RelayTcpProbeResult(succeeded = true, statusCode = 204) },
                    udpProbe =
                        RelayUdpAssociateProbe {
                            udpProbeCalls++
                            RelayUdpProbeResult.failure(RelayProbeFailure.UdpReadTimeout)
                        },
                )

            val result =
                probe.probe(
                    endpoint = RelayProbeEndpoint("127.0.0.1", 1080),
                    url = "https://probe.example/generate_204",
                    requirements = EgressRequirements(tcpConnect = true, udpAssociate = false),
                )

            assertTrue(result.succeeded)
            assertNull(result.failure)
            assertEquals(0, udpProbeCalls)
        }

    @Test
    fun `socks udp probe accepts a matching dns response`() {
        SocksUdpFixture(Behavior.Respond).use { fixture ->
            val result = runBlocking { fixture.probe.probe(RelayProbeEndpoint("127.0.0.1", fixture.port)) }

            assertTrue(result.succeeded)
            assertNull(result.failure)
        }
    }

    @Test
    fun `socks udp probe classifies a missing dns response as read timeout`() {
        SocksUdpFixture(Behavior.Blackhole).use { fixture ->
            val result = runBlocking { fixture.probe.probe(RelayProbeEndpoint("127.0.0.1", fixture.port)) }

            assertFalse(result.succeeded)
            assertTrue(result.associationOpened)
            assertEquals(RelayProbeFailure.UdpReadTimeout, result.failure)
        }
    }
}

private enum class Behavior {
    Respond,
    Blackhole,
}

private class SocksUdpFixture(
    private val behavior: Behavior,
) : AutoCloseable {
    private val tcp = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val ready = CountDownLatch(1)
    private val worker = thread(name = "socks-udp-probe-fixture") { serve() }

    val port: Int = tcp.localPort
    val probe =
        Socks5DnsUdpAssociateProbe(
            dnsTarget = InetSocketAddress("203.0.113.53", 53),
            timeoutMillis = 100,
        )

    init {
        check(ready.await(1, TimeUnit.SECONDS)) { "SOCKS UDP fixture did not start" }
    }

    private fun serve() {
        ready.countDown()
        runCatching {
            tcp.accept().use { client ->
                client.soTimeout = 1_000
                val input = DataInputStream(client.getInputStream())
                val output = DataOutputStream(client.getOutputStream())
                assertEquals(listOf(5, 1, 0), input.readNBytes(3).map(Byte::toInt))
                output.write(byteArrayOf(5, 0))
                output.flush()
                assertEquals(5, input.readUnsignedByte())
                assertEquals(3, input.readUnsignedByte())
                assertEquals(0, input.readUnsignedByte())
                skipAddress(input)

                DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { udp ->
                    val address = udp.localAddress.address
                    output.write(byteArrayOf(5, 0, 0, 1))
                    output.write(address)
                    output.writeShort(udp.localPort)
                    output.flush()

                    val request = DatagramPacket(ByteArray(512), 512)
                    udp.receive(request)
                    if (behavior == Behavior.Respond) {
                        val frame = request.data.copyOf(request.length)
                        val dnsOffset = 10
                        frame[dnsOffset + 2] = (frame[dnsOffset + 2].toInt() or 0x80).toByte()
                        udp.send(DatagramPacket(frame, frame.size, request.socketAddress))
                    }
                    input.read()
                }
            }
        }
    }

    override fun close() {
        tcp.close()
        worker.join(1_000)
    }
}

private fun skipAddress(input: DataInputStream) {
    when (input.readUnsignedByte()) {
        1 -> input.readNBytes(4)
        3 -> input.readNBytes(input.readUnsignedByte())
        4 -> input.readNBytes(16)
        else -> error("Unsupported test SOCKS address")
    }
    input.readUnsignedShort()
}
