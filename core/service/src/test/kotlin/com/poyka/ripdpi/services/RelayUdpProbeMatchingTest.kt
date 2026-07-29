package com.poyka.ripdpi.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class RelayUdpProbeMatchingTest {
    @Test
    fun `payload probe discards delayed response from previous attempt`() {
        DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { relay ->
            DatagramSocket().use { client ->
                val relayAddress = relay.localSocketAddress as InetSocketAddress
                val target = InetSocketAddress("203.0.113.53", DnsPort)
                val serverFailure = AtomicReference<Throwable?>()
                val worker =
                    thread(name = "relay-udp-delayed-response") {
                        try {
                            val first = relay.receiveFrame()
                            val second = relay.receiveFrame()
                            relay.sendFrame(first.asDnsResponse(), second.source)
                            relay.sendFrame(second.bytes.asDnsResponse(), second.source)
                        } catch (failure: Throwable) {
                            serverFailure.set(failure)
                        }
                    }

                val firstAcknowledged =
                    sendDnsProbePayload(client, relayAddress, target, dnsQuery(FirstTransactionId), ShortTimeoutMillis)
                val secondAcknowledged =
                    sendDnsProbePayload(client, relayAddress, target, dnsQuery(SecondTransactionId), TestTimeoutMillis)

                worker.join(TestThreadJoinMillis)
                assertNull(serverFailure.get())
                assertFalse(firstAcknowledged)
                assertTrue(secondAcknowledged)
            }
        }
    }

    @Test
    fun `payload probe discards wrong relay and embedded target`() {
        DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { relay ->
            DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { unrelatedSender ->
                DatagramSocket().use { client ->
                    val relayAddress = relay.localSocketAddress as InetSocketAddress
                    val target = InetSocketAddress("203.0.113.53", DnsPort)
                    val serverFailure = AtomicReference<Throwable?>()
                    val worker =
                        thread(name = "relay-udp-unrelated-response") {
                            try {
                                val request = relay.receiveFrame()
                                unrelatedSender.sendFrame(request.bytes.asDnsResponse(), request.source)
                                relay.sendFrame(
                                    request.bytes.withDifferentIpv4Target().asDnsResponse(),
                                    request.source,
                                )
                                relay.sendFrame(request.bytes.asDnsResponse(), request.source)
                            } catch (failure: Throwable) {
                                serverFailure.set(failure)
                            }
                        }

                    val acknowledged =
                        sendDnsProbePayload(
                            client,
                            relayAddress,
                            target,
                            dnsQuery(FirstTransactionId),
                            TestTimeoutMillis,
                        )

                    worker.join(TestThreadJoinMillis)
                    assertNull(serverFailure.get())
                    assertTrue(acknowledged)
                }
            }
        }
    }
}

private data class ReceivedDatagram(
    val bytes: ByteArray,
    val source: InetSocketAddress,
) {
    fun asDnsResponse(): ByteArray = bytes.asDnsResponse()
}

private fun DatagramSocket.receiveFrame(): ReceivedDatagram {
    val packet = DatagramPacket(ByteArray(MaxTestDatagramBytes), MaxTestDatagramBytes)
    receive(packet)
    return ReceivedDatagram(
        bytes = packet.data.copyOf(packet.length),
        source = packet.socketAddress as InetSocketAddress,
    )
}

private fun DatagramSocket.sendFrame(
    bytes: ByteArray,
    target: InetSocketAddress,
) {
    send(DatagramPacket(bytes, bytes.size, target))
}

private fun ByteArray.asDnsResponse(): ByteArray =
    copyOf().also { response ->
        val dnsOffset = socksPayloadOffset(response)
        response[dnsOffset + DnsFlagsHighByteOffset] =
            (response[dnsOffset + DnsFlagsHighByteOffset].toInt() or DnsResponseFlagHighByte).toByte()
    }

private fun ByteArray.withDifferentIpv4Target(): ByteArray =
    copyOf().also { response ->
        check(response[SocksAddressTypeOffset] == SocksIpv4AddressType)
        response[SocksAddressOffset] = DifferentIpv4FirstOctet
    }

private fun dnsQuery(transactionId: Int): ByteArray =
    ByteArray(TestDnsQueryBytes).also { query ->
        query[DnsTransactionHighByteOffset] = (transactionId ushr ByteBits).toByte()
        query[DnsTransactionLowByteOffset] = transactionId.toByte()
        query[DnsFlagsHighByteOffset] = DnsRecursionDesiredHighByte
        query[DnsQuestionCountLowByteOffset] = 1
    }

private fun socksPayloadOffset(frame: ByteArray): Int {
    check(frame[SocksAddressTypeOffset] == SocksIpv4AddressType)
    return SocksAddressOffset + Ipv4AddressBytes + SocksPortBytes
}

private const val DnsPort = 53
private const val FirstTransactionId = 0x1234
private const val SecondTransactionId = 0x5678
private const val ShortTimeoutMillis = 50
private const val TestTimeoutMillis = 500
private const val TestThreadJoinMillis = 1_000L
private const val MaxTestDatagramBytes = 4_096
private const val TestDnsQueryBytes = 29
private const val DnsTransactionHighByteOffset = 0
private const val DnsTransactionLowByteOffset = 1
private const val DnsFlagsHighByteOffset = 2
private const val DnsQuestionCountLowByteOffset = 5
private const val DnsRecursionDesiredHighByte: Byte = 1
private const val DnsResponseFlagHighByte = 0x80
private const val SocksAddressTypeOffset = 3
private const val SocksAddressOffset = 4
private const val SocksPortBytes = 2
private const val Ipv4AddressBytes = 4
private const val ByteBits = 8
private const val SocksIpv4AddressType: Byte = 1
private const val DifferentIpv4FirstOctet: Byte = 198.toByte()
