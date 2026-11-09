package com.poyka.ripdpi.diagnostics.dpi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun chromeFailsOthersSucceedReturnsFingerprintBlock() =
        runTest {
            val result =
                probe(
                    successes =
                        setOf(
                            QuicFingerprint.FIREFOX_121,
                            QuicFingerprint.GENERIC_V1,
                            QuicFingerprint.VN_PROBE,
                        ),
                ).check("example.com")

            assertEquals(QuicProbeVerdict.QUIC_DPI_FINGERPRINT_BLOCK, result.verdict)
            assertFalse(result.chromeOk)
            assertTrue(result.firefoxOk)
            assertTrue(result.genericOk)
            assertTrue(result.vnOk)
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
    fun nonQuicReplyIsNotResentAndTheTargetKeepsOneTimeBudget() =
        runTest {
            val socket =
                RecordingQuicUdpProbe(
                    successes = emptySet(),
                    nonQuicResponses = setOf(QuicFingerprint.GENERIC_V1),
                    nonQuicReplyDelayMs = 500,
                )
            val probe =
                QuicH3FingerprintProbe(
                    socket = socket,
                    packetFactory = QuicFingerprintFactory::createSynthetic,
                    responseValidator = AcceptSuccessfulReplies,
                    timeoutMs = 3_000,
                    timeSource = testScheduler.timeSource,
                )

            probe.check("example.com")

            // First pass: three silent sends (3 x 3 s) and one non-QUIC reply after 0.5 s. The three lost
            // fingerprints are resent once, together, with the 2.5 s left of the 4 x 3 s target budget.
            assertEquals(
                listOf(
                    QuicFingerprint.CHROME_120,
                    QuicFingerprint.FIREFOX_121,
                    QuicFingerprint.GENERIC_V1,
                    QuicFingerprint.VN_PROBE,
                    QuicFingerprint.CHROME_120,
                    QuicFingerprint.FIREFOX_121,
                    QuicFingerprint.VN_PROBE,
                ),
                socket.probedFingerprints,
            )
            assertEquals(listOf(2_500, 2_500, 2_500), socket.sendTimeoutsMs.drop(4))
            assertEquals(12_000L, testScheduler.currentTime)
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
                    override suspend fun sendInitial(
                        target: String,
                        port: Int,
                        fingerprint: QuicFingerprint,
                        packet: ByteArray,
                        timeoutMs: Int,
                    ): QuicProbeSample = QuicProbeSample(response = packet, latencyMs = 5L)
                }

            val result = probe(reflector).check("echo.example")

            assertFalse(result.chromeOk || result.firefoxOk || result.genericOk || result.vnOk)
        }

    @Test
    fun unresolvableTargetDoesNotFailTheOtherTargets() =
        runTest {
            val healthy = RecordingQuicUdpProbe(successes = QuicFingerprint.entries.toSet())
            val socket =
                object : QuicUdpProbe {
                    override suspend fun sendInitial(
                        target: String,
                        port: Int,
                        fingerprint: QuicFingerprint,
                        packet: ByteArray,
                        timeoutMs: Int,
                    ): QuicProbeSample {
                        if (target == "x.com/i/api") throw java.net.UnknownHostException(target)
                        return healthy.sendInitial(target, port, fingerprint, packet, timeoutMs)
                    }
                }

            val results = probe(socket).checkAll(listOf("x.com/i/api", "cloudflare.com"))

            assertEquals(
                listOf(QuicProbeVerdict.QUIC_TIMEOUT, QuicProbeVerdict.QUIC_OK),
                results.map(QuicProbeResult::verdict),
            )
        }

    private fun probe(successes: Set<QuicFingerprint>): QuicH3FingerprintProbe =
        probe(RecordingQuicUdpProbe(successes = successes))

    private fun probe(socket: QuicUdpProbe): QuicH3FingerprintProbe =
        QuicH3FingerprintProbe(
            socket = socket,
            packetFactory = QuicFingerprintFactory::createSynthetic,
            responseValidator = AcceptSuccessfulReplies,
        )
}

private class RecordingQuicUdpProbe(
    private val successes: Set<QuicFingerprint>,
    private val nonQuicResponses: Set<QuicFingerprint> = emptySet(),
    private val droppedFirstSends: Int = 0,
    private val nonQuicReplyDelayMs: Long = 0,
) : QuicUdpProbe {
    val probedFingerprints = mutableListOf<QuicFingerprint>()
    val sendTimeoutsMs = mutableListOf<Int>()

    override suspend fun sendInitial(
        target: String,
        port: Int,
        fingerprint: QuicFingerprint,
        packet: ByteArray,
        timeoutMs: Int,
    ): QuicProbeSample {
        probedFingerprints += fingerprint
        sendTimeoutsMs += timeoutMs
        return when {
            probedFingerprints.size <= droppedFirstSends -> {
                silence(timeoutMs)
            }

            fingerprint in successes -> {
                QuicProbeSample(
                    response = SuccessfulReply,
                    latencyMs = if (fingerprint == QuicFingerprint.CHROME_120) 14L else 20L,
                )
            }

            fingerprint in nonQuicResponses -> {
                delay(nonQuicReplyDelayMs)
                QuicProbeSample(
                    response = byteArrayOf(0x01, 0x02, 0x03),
                    latencyMs = 25L,
                )
            }

            else -> {
                silence(timeoutMs)
            }
        }
    }

    /** A real socket waits out its whole timeout when nothing answers. */
    private suspend fun silence(timeoutMs: Int): QuicProbeSample {
        delay(timeoutMs.toLong())
        return QuicProbeSample(response = null, latencyMs = null)
    }
}

// These tokens exercise verdict and retry orchestration; native RFC tests own wire validation.
private val SuccessfulReply = byteArrayOf(0x7f)
private val AcceptSuccessfulReplies =
    QuicProbeResponseValidator {
        _,
        response,
        _,
        ->
        response.contentEquals(SuccessfulReply)
    }
