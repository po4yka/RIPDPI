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
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RelayCapabilityProbeTest {
    @Test
    fun `post-association IO is not reported as association-open failure`() {
        assertEquals(RelayProbeFailure.UdpAssociateOpen, classifyUdpAssociationIoFailure(false))
        assertEquals(RelayProbeFailure.UdpIo, classifyUdpAssociationIoFailure(true))
    }

    @Test
    fun `wildcard socks udp bind address uses established control socket address`() {
        val controlAddress = InetAddress.getByName("127.0.0.42")

        assertEquals(
            controlAddress,
            effectiveUdpRelayAddress(
                relayAddress = InetAddress.getByName("0.0.0.0"),
                controlRemoteAddress = controlAddress,
            ),
        )
        assertEquals(
            InetAddress.getByName("203.0.113.7"),
            effectiveUdpRelayAddress(
                relayAddress = InetAddress.getByName("203.0.113.7"),
                controlRemoteAddress = controlAddress,
            ),
        )
    }

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

    @Test
    fun `socks udp probe rejects a failed udp association`() {
        SocksUdpFixture(Behavior.RejectAssociation).use { fixture ->
            val result = runBlocking { fixture.probe.probe(RelayProbeEndpoint("127.0.0.1", fixture.port)) }

            assertFalse(result.succeeded)
            assertEquals(RelayProbeFailure.UdpAssociateOpen, result.failure)
        }
    }

    @Test
    fun `socks udp probe rejects a non-response dns payload`() {
        SocksUdpFixture(Behavior.MalformedDns).use { fixture ->
            val result = runBlocking { fixture.probe.probe(RelayProbeEndpoint("127.0.0.1", fixture.port)) }

            assertFalse(result.succeeded)
            assertEquals(RelayProbeFailure.DnsResponse, result.failure)
        }
    }

    @Test
    fun `payload classifier reports size correlated loss without mtu blackhole`() {
        val classification =
            classifyRelayUdpPayloadFamily(
                preControlAcknowledged = true,
                attempts =
                    listOf(
                        RelayUdpPayloadProbeAttempt(payloadSizeBytes = 256, acknowledged = true),
                        RelayUdpPayloadProbeAttempt(payloadSizeBytes = 512, acknowledged = true),
                        RelayUdpPayloadProbeAttempt(payloadSizeBytes = 960, acknowledged = false),
                        RelayUdpPayloadProbeAttempt(payloadSizeBytes = 960, acknowledged = false),
                    ),
                postControlAcknowledged = true,
            )

        assertEquals(512, classification.maxAcknowledgedPayloadBytes)
        assertEquals(960, classification.firstRepeatedFailedPayloadBytes)
        assertEquals(
            RelayUdpPayloadHealthVerdict.InconclusiveSizeCorrelatedLoss,
            classification.verdict,
        )
        assertFalse(classification.verdict.wireValue.contains("MTU_BLACKHOLE", ignoreCase = true))
    }

    @Test
    fun `payload classifier fails closed when controls fail`() {
        val classification =
            classifyRelayUdpPayloadFamily(
                preControlAcknowledged = false,
                attempts = emptyList(),
                postControlAcknowledged = null,
            )

        assertEquals(RelayUdpPayloadControlOutcome.Failed, classification.controlBefore)
        assertEquals(RelayUdpPayloadControlOutcome.NotAttempted, classification.controlAfter)
        assertEquals(RelayUdpPayloadHealthVerdict.InconclusiveControlFailed, classification.verdict)
        assertEquals(1, classification.attemptCount)
    }

    @Test
    fun `socks payload ladder records ipv4 ipv6 repeated failure and recovered control`() {
        SocksUdpFixture(
            behavior = Behavior.PayloadLadder,
            payloadAckMaxBytes = 1_232,
        ).use { fixture ->
            val result =
                runBlocking {
                    fixture.payloadProbe.probe(
                        endpoint = RelayProbeEndpoint("127.0.0.1", fixture.port),
                        families = setOf(RelayUdpPayloadFamily.Ipv4, RelayUdpPayloadFamily.Ipv6),
                    )
                }

            assertEquals(
                RelayUdpPayloadHealthVerdict.InconclusiveSizeCorrelatedLoss.wireValue,
                result.overallVerdict,
            )
            assertTrue(result.passed)
            assertEquals(listOf("ipv4", "ipv6"), result.families.map { it.family })
            result.families.forEach { family ->
                assertEquals(RelayUdpPayloadControlOutcome.Acknowledged.wireValue, family.controlBefore)
                assertEquals(RelayUdpPayloadControlOutcome.Acknowledged.wireValue, family.controlAfter)
                assertEquals(1_232, family.maxAcknowledgedPayloadBytes)
                assertEquals(1_400, family.firstRepeatedFailedPayloadBytes)
                assertEquals(8, family.attemptCount)
                assertEquals(
                    RelayUdpPayloadHealthVerdict.InconclusiveSizeCorrelatedLoss.wireValue,
                    family.verdict,
                )
                assertEquals(RelayUdpPayloadPathSignal.NotObservable.wireValue, family.ptbObservation)
                assertEquals(RelayUdpPayloadPathSignal.NotObservable.wireValue, family.fragmentationReassembly)
            }
            assertEquals(listOf(1, 1, 1, 1, 1, 1, 1, 1, 4, 4, 4, 4, 4, 4, 4, 4), fixture.addressTypes())
            assertEquals(
                listOf(
                    64,
                    256,
                    512,
                    960,
                    1_232,
                    1_400,
                    1_400,
                    64,
                    64,
                    256,
                    512,
                    960,
                    1_232,
                    1_400,
                    1_400,
                    64,
                ),
                fixture.payloadSizes(),
            )
        }
    }

    @Test
    fun `socks payload ladder stops when pre control is not acknowledged`() {
        SocksUdpFixture(
            behavior = Behavior.PayloadLadder,
            failPreControl = true,
        ).use { fixture ->
            val result =
                runBlocking {
                    fixture.payloadProbe.probe(
                        endpoint = RelayProbeEndpoint("127.0.0.1", fixture.port),
                        families = setOf(RelayUdpPayloadFamily.Ipv4),
                    )
                }

            val family = result.families.single()
            assertEquals(RelayUdpPayloadControlOutcome.Failed.wireValue, family.controlBefore)
            assertEquals(RelayUdpPayloadControlOutcome.NotAttempted.wireValue, family.controlAfter)
            assertEquals(RelayUdpPayloadHealthVerdict.InconclusiveControlFailed.wireValue, family.verdict)
            assertEquals(1, family.attemptCount)
            assertEquals(listOf(64), fixture.payloadSizes())
        }
    }
}

private enum class Behavior {
    Respond,
    Blackhole,
    MalformedDns,
    RejectAssociation,
    PayloadLadder,
}

private class SocksUdpFixture(
    private val behavior: Behavior,
    private val payloadAckMaxBytes: Int = Int.MAX_VALUE,
    private val failPreControl: Boolean = false,
) : AutoCloseable {
    private val tcp = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    private val ready = CountDownLatch(1)
    private val requests = Collections.synchronizedList(mutableListOf<UdpFixtureRequest>())
    private val handlers = CopyOnWriteArrayList<Thread>()
    private val worker = thread(name = "socks-udp-probe-fixture") { serve() }

    val port: Int = tcp.localPort
    val probe =
        Socks5DnsUdpAssociateProbe(
            dnsTarget = InetSocketAddress("203.0.113.53", 53),
            timeoutMillis = 100,
        )
    val payloadProbe =
        Socks5DnsUdpPayloadHealthProbe(
            targets =
                mapOf(
                    RelayUdpPayloadFamily.Ipv4 to InetSocketAddress("203.0.113.53", 53),
                    RelayUdpPayloadFamily.Ipv6 to
                        InetSocketAddress(InetAddress.getByName("2001:db8::53"), 53),
                ),
            timeoutMillis = 500,
            payloadSizesBytes = listOf(256, 512, 960, 1_232, 1_400),
        )

    init {
        check(ready.await(1, TimeUnit.SECONDS)) { "SOCKS UDP fixture did not start" }
    }

    private fun serve() {
        ready.countDown()
        runCatching {
            while (!tcp.isClosed) {
                val client = tcp.accept()
                handlers +=
                    thread(name = "socks-udp-probe-fixture-client", isDaemon = true) {
                        client.use(::handleClient)
                    }
            }
        }
    }

    private fun handleClient(client: Socket) {
        runCatching {
            client.soTimeout = 1_000
            val input = DataInputStream(client.getInputStream())
            val output = DataOutputStream(client.getOutputStream())
            input.use {
                assertEquals(listOf(5, 1, 0), input.readNBytes(3).map(Byte::toInt))
                output.write(byteArrayOf(5, 0))
                output.flush()
                assertEquals(5, input.readUnsignedByte())
                assertEquals(3, input.readUnsignedByte())
                assertEquals(0, input.readUnsignedByte())
                skipAddress(input)

                if (behavior == Behavior.RejectAssociation) {
                    output.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, 0, 1))
                    output.flush()
                    return@runCatching
                }

                DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { udp ->
                    udp.soTimeout = 1_000
                    val address = udp.localAddress.address
                    output.write(byteArrayOf(5, 0, 0, 1))
                    output.write(address)
                    output.writeShort(udp.localPort)
                    output.flush()

                    var requestIndex = 0
                    while (true) {
                        val request = DatagramPacket(ByteArray(4_096), 4_096)
                        try {
                            udp.receive(request)
                        } catch (_: SocketTimeoutException) {
                            break
                        }
                        val frame = request.data.copyOf(request.length)
                        val dnsOffset = socksPayloadOffset(frame, request.length)
                        val payloadSize = request.length - dnsOffset
                        requests += UdpFixtureRequest(addressType = frame[3].toInt(), payloadSize = payloadSize)
                        if (shouldRespond(requestIndex, payloadSize)) {
                            if (behavior != Behavior.MalformedDns) {
                                frame[dnsOffset + 2] = (frame[dnsOffset + 2].toInt() or 0x80).toByte()
                            }
                            udp.send(DatagramPacket(frame, frame.size, request.socketAddress))
                        }
                        requestIndex += 1
                    }
                }
            }
        }
    }

    override fun close() {
        tcp.close()
        handlers.forEach { it.join(1_000) }
        worker.join(1_000)
    }

    fun payloadSizes(): List<Int> = requests.map(UdpFixtureRequest::payloadSize)

    fun addressTypes(): List<Int> = requests.map(UdpFixtureRequest::addressType)

    private fun shouldRespond(
        requestIndex: Int,
        payloadSize: Int,
    ): Boolean =
        when (behavior) {
            Behavior.Respond -> true
            Behavior.Blackhole -> false
            Behavior.MalformedDns -> true
            Behavior.RejectAssociation -> false
            Behavior.PayloadLadder -> !(failPreControl && requestIndex == 0) && payloadSize <= payloadAckMaxBytes
        }
}

private data class UdpFixtureRequest(
    val addressType: Int,
    val payloadSize: Int,
)

private fun skipAddress(input: DataInputStream) {
    when (input.readUnsignedByte()) {
        1 -> input.readNBytes(4)
        3 -> input.readNBytes(input.readUnsignedByte())
        4 -> input.readNBytes(16)
        else -> error("Unsupported test SOCKS address")
    }
    input.readUnsignedShort()
}

private fun socksPayloadOffset(
    frame: ByteArray,
    length: Int,
): Int {
    val addressLength =
        when (frame[3].toInt()) {
            1 -> {
                4
            }

            3 -> {
                check(length >= 5) { "Invalid domain frame" }
                1 + frame[4].toUByte().toInt()
            }

            4 -> {
                16
            }

            else -> {
                error("Unsupported test SOCKS UDP address")
            }
        }
    return 4 + addressLength + 2
}
