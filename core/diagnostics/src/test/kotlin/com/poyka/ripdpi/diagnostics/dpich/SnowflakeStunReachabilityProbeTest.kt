package com.poyka.ripdpi.diagnostics.dpich

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketAddress

class SnowflakeStunReachabilityProbeTest {
    @Test
    fun acceptsOnlyMatchingResponseAfterUnrelatedPackets() =
        runBlocking {
            val trace =
                exchange {
                    val request = receiveRequest()
                    assertArrayEquals(BindingRequest, request.data.copyOf(request.length))
                    DatagramSocket(0, Loopback).use { wrongPeer ->
                        wrongPeer.reply(request.socketAddress, BindingSuccess)
                    }
                    reply(request.socketAddress, BindingSuccess.copyOf().apply { this[8] = (this[8] + 1).toByte() })
                    reply(request.socketAddress, BindingSuccess)
                }

            assertEquals(PtProbeStatus.BINDING_RESPONSE, trace.status)
        }

    @Test
    fun rejectsMatchingSuccessWithMissingOrTruncatedAttributes() =
        runBlocking {
            val trace =
                exchange(timeoutMs = 250) {
                    val request = receiveRequest()
                    reply(request.socketAddress, BindingSuccess.copyOf(20).apply { this[3] = 0 })
                    reply(request.socketAddress, BindingSuccess.copyOf().apply { this[23] = 12 })
                }

            assertEquals(PtProbeStatus.INVALID_RESPONSE, trace.status)
        }

    @Test
    fun rejectsUnknownRequiredAttributeAndIgnoresUnknownOptionalAttribute() =
        runBlocking {
            val required =
                exchange(timeoutMs = 250) {
                    val request = receiveRequest()
                    val response = BindingSuccess.copyOf().apply { this[3] = 16 } + "7fff0000".hexToByteArray()
                    reply(request.socketAddress, response)
                }
            val optional =
                exchange {
                    val request = receiveRequest()
                    val response = BindingSuccess.copyOf().apply { this[3] = 16 } + "ffff0000".hexToByteArray()
                    reply(request.socketAddress, response)
                }

            assertEquals(PtProbeStatus.INVALID_RESPONSE, required.status)
            assertEquals(PtProbeStatus.BINDING_RESPONSE, optional.status)
        }

    @Test
    fun retransmitsSameTransactionAfterPacketLoss() =
        runBlocking {
            val trace =
                exchange(timeoutMs = 2_000) {
                    val first = receiveRequest()
                    val retry = receiveRequest()
                    assertArrayEquals(first.data.copyOf(first.length), retry.data.copyOf(retry.length))
                    reply(retry.socketAddress, BindingSuccess)
                }

            assertEquals(PtProbeStatus.BINDING_RESPONSE, trace.status)
        }

    @Test
    fun noReplyReportsTimeout() =
        runBlocking {
            val trace = exchange(timeoutMs = 250) { receiveRequest() }

            assertEquals(PtProbeStatus.TIMEOUT, trace.status)
        }

    @Test
    fun cancellationClosesSocketWhileWaitingForTheBindingResponse() =
        runBlocking {
            DatagramSocket(0, Loopback).use { server ->
                DatagramSocket().use { client ->
                    server.soTimeout = 5_000
                    val inbound = async(Dispatchers.IO) { server.receiveRequest() }
                    val probe =
                        SnowflakeStunReachabilityProbe(
                            endpoint = PtEndpoint(Loopback.hostAddress!!, server.localPort),
                            socketFactory = { client },
                        )
                    val job = launch(Dispatchers.IO) { probe.run(8_000) }
                    inbound.await()

                    withTimeout(2_000) { job.cancelAndJoin() }

                    assertTrue(client.isClosed)
                }
            }
        }

    private suspend fun exchange(
        timeoutMs: Long = 1_000,
        respond: DatagramSocket.() -> Unit,
    ): PtProbeTrace =
        coroutineScope {
            DatagramSocket(0, Loopback).use { server ->
                server.soTimeout = 3_000
                val peer = async(Dispatchers.IO) { server.respond() }
                val probe =
                    SnowflakeStunReachabilityProbe(
                        endpoint = PtEndpoint(Loopback.hostAddress!!, server.localPort),
                        transactionId = { BindingRequest.copyOfRange(8, 20) },
                    )
                val trace = probe.run(timeoutMs)
                peer.await()
                trace
            }
        }

    private fun DatagramSocket.receiveRequest(): DatagramPacket = DatagramPacket(ByteArray(256), 256).also(::receive)

    private fun DatagramSocket.reply(
        target: SocketAddress,
        message: ByteArray,
    ) {
        send(DatagramPacket(message, message.size, target))
    }

    private companion object {
        private val Loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private val BindingRequest = "000100002112a442000102030405060708090a0b".hexToByteArray()

        // RFC 8489 §14.2, IPv4 address 127.0.0.1 and port 3478.
        private val BindingSuccess = "0101000c2112a442000102030405060708090a0b0020000800012c845e12a443".hexToByteArray()
    }
}
