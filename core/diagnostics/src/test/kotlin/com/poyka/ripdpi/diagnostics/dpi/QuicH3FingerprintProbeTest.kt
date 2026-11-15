package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TimeMark

class QuicH3FingerprintProbeTest {
    @Test
    fun allFingerprintsSucceedReturnsOk() =
        runTest {
            val result = probe(successes = QuicFingerprint.entries.toSet()).check("cloudflare.com")

            assertEquals(QuicProbeVerdict.QUIC_OK, result.verdict)
            assertTrue(result.udpReachable)
            assertTrue(result.chromeOk)
            assertTrue(result.firefoxOk)
            assertTrue(result.genericOk)
            assertTrue(result.vnOk)
            assertEquals(14L, result.serverInitialLatencyMs)
        }

    @Test
    fun allFingerprintTimeoutsReturnTimeout() =
        runTest {
            val socket = RecordingQuicUdpProbe(successes = emptySet())

            val result = probe(socket).check("blocked.example")

            assertEquals(QuicProbeVerdict.QUIC_TIMEOUT, result.verdict)
            assertFalse(result.udpReachable)
            assertEquals(
                listOf(
                    QuicFingerprint.CHROME_120,
                    QuicFingerprint.FIREFOX_121,
                    QuicFingerprint.GENERIC_V1,
                    QuicFingerprint.VN_PROBE,
                ),
                socket.probedFingerprints,
            )
        }

    @Test
    fun versionNegotiationResultIsProbedAfterV1Fingerprints() =
        runTest {
            val socket =
                RecordingQuicUdpProbe(
                    successes =
                        setOf(
                            QuicFingerprint.CHROME_120,
                            QuicFingerprint.FIREFOX_121,
                            QuicFingerprint.GENERIC_V1,
                        ),
                )

            val result = probe(socket).check("example.com")

            assertEquals(QuicProbeVerdict.QUIC_VN_REJECTED, result.verdict)
            assertEquals(
                listOf(
                    QuicFingerprint.CHROME_120,
                    QuicFingerprint.FIREFOX_121,
                    QuicFingerprint.GENERIC_V1,
                    QuicFingerprint.VN_PROBE,
                    QuicFingerprint.VN_PROBE,
                ),
                socket.probedFingerprints,
            )
        }

    @Test
    fun genericFingerprintFailureStillRunsFullFingerprintMatrix() =
        runTest {
            val socket =
                RecordingQuicUdpProbe(
                    successes =
                        setOf(
                            QuicFingerprint.CHROME_120,
                            QuicFingerprint.FIREFOX_121,
                            QuicFingerprint.VN_PROBE,
                        ),
                )

            val result = probe(socket).check("example.com")

            assertEquals(QuicProbeVerdict.QUIC_DEGRADED, result.verdict)
            assertTrue(result.udpReachable)
            assertEquals(
                listOf(
                    QuicFingerprint.CHROME_120,
                    QuicFingerprint.FIREFOX_121,
                    QuicFingerprint.GENERIC_V1,
                    QuicFingerprint.VN_PROBE,
                    QuicFingerprint.GENERIC_V1,
                ),
                socket.probedFingerprints,
            )
        }

    @Test
    fun anySingleV1TemplateLossReportsDegraded() =
        runTest {
            val v1Templates = QuicFingerprint.entries.filterNot { it == QuicFingerprint.VN_PROBE }
            for (lost in v1Templates) {
                val result = probe(QuicFingerprint.entries.toSet() - lost).check("example.com")

                assertEquals("Lost template: $lost", QuicProbeVerdict.QUIC_DEGRADED, result.verdict)
                assertEquals(lost != QuicFingerprint.CHROME_120, result.chromeOk)
                assertEquals(lost != QuicFingerprint.FIREFOX_121, result.firefoxOk)
                assertEquals(lost != QuicFingerprint.GENERIC_V1, result.genericOk)
                assertTrue(result.vnOk)
            }
        }

    @Test
    fun vnProbeFailsOthersSucceedReturnsVnRejected() =
        runTest {
            val result =
                probe(
                    successes =
                        setOf(
                            QuicFingerprint.CHROME_120,
                            QuicFingerprint.FIREFOX_121,
                            QuicFingerprint.GENERIC_V1,
                        ),
                ).check("example.com")

            assertEquals(QuicProbeVerdict.QUIC_VN_REJECTED, result.verdict)
        }

    @Test
    fun allFingerprintsReturnNonQuicResponsesReturnsDropped() =
        runTest {
            val result =
                probe(
                    RecordingQuicUdpProbe(
                        successes = emptySet(),
                        nonQuicResponses = QuicFingerprint.entries.toSet(),
                    ),
                ).check("example.com")

            assertEquals(QuicProbeVerdict.QUIC_DROPPED, result.verdict)
            assertTrue(result.udpReachable)
        }

    @Test
    fun mixedResultsReturnDegraded() =
        runTest {
            val result =
                probe(
                    successes =
                        setOf(
                            QuicFingerprint.CHROME_120,
                            QuicFingerprint.FIREFOX_121,
                            QuicFingerprint.VN_PROBE,
                        ),
                ).check("example.com")

            assertEquals(QuicProbeVerdict.QUIC_DEGRADED, result.verdict)
            assertTrue(result.chromeOk)
            assertTrue(result.firefoxOk)
            assertFalse(result.genericOk)
            assertTrue(result.vnOk)
        }

    @Test
    fun firstLostDatagramIsRetriedBeforeBlamingTheFingerprint() =
        runTest {
            val socket = RecordingQuicUdpProbe(successes = QuicFingerprint.entries.toSet(), droppedFirstSends = 1)

            val result = probe(socket).check("example.com")

            assertEquals(QuicProbeVerdict.QUIC_OK, result.verdict)
            assertEquals(QuicFingerprint.CHROME_120, socket.probedFingerprints.last())
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retriesOnlyMissingTemplatesWithinTheTargetDeadline() =
        runTest {
            val socket =
                RecordingQuicUdpProbe(
                    successes = setOf(QuicFingerprint.GENERIC_V1),
                    successfulReplyDelayMs = 500,
                )
            val probe =
                QuicH3FingerprintProbe(
                    socket = socket,
                    packetFactory = QuicFingerprintFactory::createSynthetic,
                    responseValidator = AcceptSuccessfulReplies,
                    timeoutMs = 3_000,
                    timeSource = testScheduler.timeSource,
                )

            val result = probe.check("example.com")

            // Three silent attempts consume nine seconds. The successful control leaves 2.5 seconds
            // for the three missing templates to retry once in parallel.
            assertEquals(listOf(2_500, 2_500, 2_500), socket.sendTimeoutsMs.drop(4))
            assertEquals(12_000L, testScheduler.currentTime)
            assertEquals(
                listOf(QuicProbeOutcome.NO_RESPONSE, QuicProbeOutcome.NO_RESPONSE),
                result.attempts.getValue(QuicFingerprint.CHROME_120).map { it.outcome },
            )
        }

    @Test
    fun versionNegotiationValidationModeIsSeparateFromInitialValidation() =
        runTest {
            val result =
                QuicH3FingerprintProbe(
                    socket = RecordingQuicUdpProbe(successes = QuicFingerprint.entries.toSet()),
                    packetFactory = QuicFingerprintFactory::createSynthetic,
                    responseValidator =
                        QuicProbeResponseValidator { _, response, vn ->
                            vn && response.contentEquals(SuccessfulReply)
                        },
                ).check("example.com")

            assertFalse(result.chromeOk || result.firefoxOk || result.genericOk)
            assertTrue(result.vnOk)
            assertEquals(QuicProbeVerdict.QUIC_DEGRADED, result.verdict)
        }

    @Test
    fun reflectedRequestIsNotCountedAsAQuicAnswer() =
        runTest {
            val reflector =
                object : QuicUdpProbe {
                    override suspend fun resolve(
                        target: String,
                        timeoutMs: Int,
                    ): InetAddress = LoopbackAddress

                    override suspend fun sendInitial(
                        address: InetAddress,
                        port: Int,
                        fingerprint: QuicFingerprint,
                        packet: ByteArray,
                        deadline: TimeMark,
                        accepts: (ByteArray) -> Boolean,
                    ): QuicProbeSample = testedReply(packet, 5L, accepts)
                }

            val result = probe(reflector).check("echo.example")

            assertFalse(result.chromeOk || result.firefoxOk || result.genericOk || result.vnOk)
        }

    @Test
    fun unresolvableTargetDoesNotFailTheOtherTargets() =
        runTest {
            val healthy = RecordingQuicUdpProbe(successes = QuicFingerprint.entries.toSet())
            val socket =
                object : QuicUdpProbe by healthy {
                    override suspend fun resolve(
                        target: String,
                        timeoutMs: Int,
                    ): InetAddress =
                        if (target == "broken.invalid") throw UnknownHostException(target) else LoopbackAddress
                }

            val results = probe(socket).checkAll(listOf("broken.invalid", "example.com"))

            assertEquals(QuicProbeStartFailure.DNS_ERROR, results[0].startFailure)
            assertTrue(results[0].attempts.isEmpty())
            assertEquals(QuicProbeVerdict.QUIC_OK, results[1].verdict)
        }

    @Test
    fun laterSocketErrorPreservesPreviousAndFollowingReplies() =
        runTest {
            val healthy = RecordingQuicUdpProbe(successes = QuicFingerprint.entries.toSet())
            val socket =
                object : QuicUdpProbe by healthy {
                    override suspend fun sendInitial(
                        address: InetAddress,
                        port: Int,
                        fingerprint: QuicFingerprint,
                        packet: ByteArray,
                        deadline: TimeMark,
                        accepts: (ByteArray) -> Boolean,
                    ): QuicProbeSample {
                        if (fingerprint == QuicFingerprint.FIREFOX_121) throw IOException("fixture socket failure")
                        return healthy.sendInitial(address, port, fingerprint, packet, deadline, accepts)
                    }
                }

            val result = probe(socket).check("example.com")

            assertEquals(QuicProbeVerdict.QUIC_DEGRADED, result.verdict)
            assertEquals(
                QuicProbeOutcome.TRANSPORT_ERROR,
                result.attempts
                    .getValue(QuicFingerprint.FIREFOX_121)
                    .single()
                    .outcome,
            )
            assertTrue(result.chromeOk && result.genericOk && result.vnOk)
        }

    @Test(timeout = 10_000)
    fun pinsOneResolvedAddressForEveryTemplateAndItsRetry() =
        runBlocking<Unit> {
            val lookups = AtomicInteger()
            val withheld = QuicFingerprint.CHROME_120.ordinal.toByte()
            DatagramSocket(0, LoopbackAddress).use { server ->
                server.soTimeout = 2_000
                val responder =
                    async(Dispatchers.IO) {
                        var withheldOnce = false
                        repeat(5) {
                            val request = DatagramPacket(ByteArray(16), 16)
                            server.receive(request)
                            if (request.data[0] != withheld || withheldOnce) {
                                server.send(
                                    DatagramPacket(SuccessfulReply, SuccessfulReply.size, request.socketAddress),
                                )
                            } else {
                                withheldOnce = true
                            }
                        }
                    }
                try {
                    val probe =
                        QuicH3FingerprintProbe(
                            socket =
                                DatagramSocketQuicUdpProbe {
                                    if (lookups.incrementAndGet() == 1) {
                                        LoopbackAddress
                                    } else {
                                        InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2))
                                    }
                                },
                            packetFactory =
                                QuicInitialPacketFactory {
                                    template,
                                    _,
                                    ->
                                    byteArrayOf(template.ordinal.toByte())
                                },
                            responseValidator = AcceptSuccessfulReplies,
                            port = server.localPort,
                            timeoutMs = 300,
                        )

                    val result = probe.check("peer.invalid")

                    assertEquals(1, lookups.get())
                    assertEquals("127.0.0.1", result.peerAddress)
                    assertEquals(QuicProbeVerdict.QUIC_OK, result.verdict)
                    assertEquals(
                        listOf(QuicProbeOutcome.NO_RESPONSE, QuicProbeOutcome.CORRELATED_RESPONSE),
                        result.attempts.getValue(QuicFingerprint.CHROME_120).map { it.outcome },
                    )
                    responder.await()
                } finally {
                    server.close()
                    responder.cancelAndJoin()
                }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.probe(successes: Set<QuicFingerprint>): QuicH3FingerprintProbe =
        probe(RecordingQuicUdpProbe(successes = successes))

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.probe(socket: QuicUdpProbe): QuicH3FingerprintProbe =
        QuicH3FingerprintProbe(
            socket = socket,
            packetFactory = QuicFingerprintFactory::createSynthetic,
            responseValidator = AcceptSuccessfulReplies,
            timeSource = testScheduler.timeSource,
        )
}

private class RecordingQuicUdpProbe(
    private val successes: Set<QuicFingerprint>,
    private val nonQuicResponses: Set<QuicFingerprint> = emptySet(),
    private val droppedFirstSends: Int = 0,
    private val successfulReplyDelayMs: Long = 0,
) : QuicUdpProbe {
    val probedFingerprints = mutableListOf<QuicFingerprint>()
    val sendTimeoutsMs = mutableListOf<Int>()

    override suspend fun resolve(
        target: String,
        timeoutMs: Int,
    ): InetAddress = LoopbackAddress

    override suspend fun sendInitial(
        address: InetAddress,
        port: Int,
        fingerprint: QuicFingerprint,
        packet: ByteArray,
        deadline: TimeMark,
        accepts: (ByteArray) -> Boolean,
    ): QuicProbeSample {
        val timeoutMs = (-deadline.elapsedNow()).inWholeMilliseconds.toInt().coerceAtLeast(0)
        probedFingerprints += fingerprint
        sendTimeoutsMs += timeoutMs
        return when {
            probedFingerprints.size <= droppedFirstSends -> {
                silence(timeoutMs)
            }

            fingerprint in successes -> {
                delay(successfulReplyDelayMs)
                testedReply(SuccessfulReply, if (fingerprint == QuicFingerprint.CHROME_120) 14L else 20L, accepts)
            }

            fingerprint in nonQuicResponses -> {
                delay(timeoutMs.toLong())
                testedReply(byteArrayOf(0x01, 0x02, 0x03), 25L, accepts)
            }

            else -> {
                silence(timeoutMs)
            }
        }
    }

    /** A real socket waits out its whole timeout when nothing answers. */
    private suspend fun silence(timeoutMs: Int): QuicProbeSample {
        delay(timeoutMs.toLong())
        return QuicProbeSample(QuicProbeOutcome.NO_RESPONSE)
    }
}

// These tokens exercise verdict and retry orchestration; native RFC tests own wire validation.
private val LoopbackAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
private val SuccessfulReply = byteArrayOf(0x7f)
private val AcceptSuccessfulReplies =
    QuicProbeResponseValidator {
        _,
        response,
        _,
        ->
        response.contentEquals(SuccessfulReply)
    }

private fun testedReply(
    response: ByteArray,
    latencyMs: Long,
    accepts: (ByteArray) -> Boolean,
): QuicProbeSample =
    if (accepts(response)) {
        QuicProbeSample(QuicProbeOutcome.CORRELATED_RESPONSE, latencyMs = latencyMs)
    } else {
        QuicProbeSample(QuicProbeOutcome.UNCORRELATED_RESPONSE, invalidDatagrams = 1)
    }
