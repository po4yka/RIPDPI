package com.poyka.ripdpi.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poyka.ripdpi.diagnostics.dpi.DatagramSocketQuicUdpProbe
import com.poyka.ripdpi.diagnostics.dpi.NativeQuicInitialPacketBindings
import com.poyka.ripdpi.diagnostics.dpi.QuicFingerprint
import com.poyka.ripdpi.diagnostics.dpi.QuicH3FingerprintProbe
import com.poyka.ripdpi.diagnostics.dpi.QuicProbeVerdict
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class QuicInitialResponseValidationInstrumentedTest {
    private val bindings = NativeQuicInitialPacketBindings()

    @Test
    fun nativeBindingValidatesIndependentV1AndV2InitialAndRetryPackets() {
        // RFC 9001 and RFC 9369 Appendices A.2-A.4; shared with native validator tests.
        for (rfc in listOf("9001", "9369")) {
            val request = fixture("rfc$rfc-client-initial.bin")
            for (name in listOf("server-initial", "retry")) {
                val response = fixture("rfc$rfc-$name.bin")
                assertTrue("RFC $rfc $name", bindings.validate(request, response, false))
                assertFalse(bindings.validate(request, response, true))
                val damaged = response.copyOf()
                damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
                assertFalse(bindings.validate(request, damaged, false))
                assertFalse(bindings.validate(request, response.copyOf(response.size - 1), false))
            }
            assertFalse(bindings.validate(request, request, false))
        }
    }

    @Test
    fun nativeBindingSeparatesReservedVersionNegotiationAndRejectsMalformedLists() {
        val initial = fixture("rfc9001-client-initial.bin")
        val response = responseHeader(initial, 0) + byteArrayOf(0x6b, 0x33, 0x43, 0xcf.toByte())
        assertFalse(bindings.validate(initial, response, false))

        val reserved = initial.copyOf()
        val offered = byteArrayOf(0x1a, 0x2a, 0x3a, 0x4a)
        offered.copyInto(reserved, 1)
        assertTrue(bindings.validate(reserved, response, true))
        assertFalse(bindings.validate(reserved, response, false))
        val header = responseHeader(reserved, 0)
        for (versions in listOf(byteArrayOf(), byteArrayOf(1), ByteArray(4), offered)) {
            assertFalse(bindings.validate(reserved, header + versions, true))
        }
        val wrongId = response.copyOf()
        wrongId[7] = (wrongId[7].toInt() xor 1).toByte()
        assertFalse(bindings.validate(reserved, wrongId, true))
        assertFalse(bindings.validate(byteArrayOf(), response, true))
    }

    @Test
    fun nativeBindingValidatesCompleteLargeUdpResponses() =
        runBlocking<Unit> {
            for (rfc in listOf("9001", "9369")) {
                val request = fixture("rfc$rfc-client-initial.bin")
                val expected = fixture("rfc$rfc-server-initial-3000.bin")
                assertTrue(bindings.validate(request, expected, false))
                assertFalse(bindings.validate(request, expected.copyOf(2_048), false))
                val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
                DatagramSocket(0, loopback).use { server ->
                    server.soTimeout = 3_000
                    val failure = AtomicReference<Throwable?>()
                    val responder =
                        thread {
                            runCatching {
                                val received = DatagramPacket(ByteArray(2_048), 2_048)
                                server.receive(received)
                                assertArrayEquals(request, received.data.copyOf(received.length))
                                server.send(DatagramPacket(expected, expected.size, received.socketAddress))
                            }.onFailure(failure::set)
                        }
                    try {
                        val sample =
                            DatagramSocketQuicUdpProbe().sendInitial(
                                "127.0.0.1",
                                server.localPort,
                                QuicFingerprint.GENERIC_V1,
                                request,
                                3_000,
                            )
                        val response = requireNotNull(sample.response)
                        assertArrayEquals(expected, response)
                        assertTrue(bindings.validate(request, response, false))
                    } finally {
                        server.close()
                        responder.join(3_000)
                    }
                    assertFalse("loopback responder did not stop", responder.isAlive)
                    failure.get()?.let { throw AssertionError("loopback fixture failed", it) }
                }
            }
        }

    @Test
    fun malformedLoopbackRepliesDoNotProduceQuicOkWithNativeGenerationAndValidation() =
        runBlocking<Unit> {
            val server = DatagramSocket(0, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            server.soTimeout = 3_000
            val failure = AtomicReference<Throwable?>()
            val worker =
                thread {
                    runCatching {
                        repeat(4) { index ->
                            val received = DatagramPacket(ByteArray(2_048), 2_048)
                            server.receive(received)
                            val request = received.data.copyOf(received.length)
                            // Header-only Initial and empty VN were previously counted as QUIC_OK.
                            val response = responseHeader(request, if (index == 3) 0 else 1)
                            server.send(DatagramPacket(response, response.size, received.address, received.port))
                        }
                    }.onFailure(failure::set)
                }
            try {
                val result = QuicH3FingerprintProbe(port = server.localPort, timeoutMs = 500).check("127.0.0.1")

                assertTrue(result.udpReachable)
                assertFalse(result.chromeOk || result.firefoxOk || result.genericOk || result.vnOk)
                assertEquals(QuicProbeVerdict.QUIC_DROPPED, result.verdict)
            } finally {
                server.close()
                worker.join(3_000)
            }
            assertFalse("loopback worker did not stop", worker.isAlive)
            failure.get()?.let { throw AssertionError("loopback fixture failed", it) }
        }

    private fun fixture(name: String): ByteArray =
        InstrumentationRegistry
            .getInstrumentation()
            .context.assets
            .open(name)
            .use { it.readBytes() }

    private fun responseHeader(
        request: ByteArray,
        version: Int,
    ): ByteArray {
        val dcidLength = request[5].toInt() and 0xff
        val dcid = request.copyOfRange(6, 6 + dcidLength)
        val scidLength = request[6 + dcidLength].toInt() and 0xff
        val scid = request.copyOfRange(7 + dcidLength, 7 + dcidLength + scidLength)
        return byteArrayOf(
            (
                if (version ==
                    0
                ) {
                    0x80
                } else {
                    0xc0
                }
            ).toByte(),
            0,
            0,
            0,
            version.toByte(),
            scidLength.toByte(),
        ) +
            scid + byteArrayOf(dcidLength.toByte()) + dcid
    }
}
