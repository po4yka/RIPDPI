package com.poyka.ripdpi.diagnostics.dpi

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

            val result = QuicH3FingerprintProbe(socket = socket).check("blocked.example")

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

            val result = QuicH3FingerprintProbe(socket = socket).check("example.com")

            assertEquals(QuicProbeVerdict.QUIC_VN_REJECTED, result.verdict)
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

            val result = QuicH3FingerprintProbe(socket = socket).check("example.com")

            assertEquals(QuicProbeVerdict.QUIC_DEGRADED, result.verdict)
            assertTrue(result.udpReachable)
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
                QuicH3FingerprintProbe(
                    socket =
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

    private fun probe(successes: Set<QuicFingerprint>): QuicH3FingerprintProbe =
        QuicH3FingerprintProbe(socket = RecordingQuicUdpProbe(successes = successes))
}

private class RecordingQuicUdpProbe(
    private val successes: Set<QuicFingerprint>,
    private val nonQuicResponses: Set<QuicFingerprint> = emptySet(),
) : QuicUdpProbe {
    val probedFingerprints = mutableListOf<QuicFingerprint>()

    override suspend fun sendInitial(
        target: String,
        port: Int,
        fingerprint: QuicFingerprint,
        packet: ByteArray,
        timeoutMs: Int,
    ): QuicProbeSample {
        probedFingerprints += fingerprint
        return when {
            fingerprint in successes -> {
                QuicProbeSample(
                    response =
                        when (fingerprint) {
                            QuicFingerprint.VN_PROBE -> quicLongHeader(version = 0)
                            else -> quicLongHeader(version = QuicFingerprintFactory.QuicV1Version)
                        },
                    latencyMs = if (fingerprint == QuicFingerprint.CHROME_120) 14L else 20L,
                )
            }

            fingerprint in nonQuicResponses -> {
                QuicProbeSample(
                    response = byteArrayOf(0x01, 0x02, 0x03),
                    latencyMs = 25L,
                )
            }

            else -> {
                QuicProbeSample(response = null, latencyMs = null)
            }
        }
    }

    private fun quicLongHeader(version: Int): ByteArray =
        byteArrayOf(
            0xC0.toByte(),
            ((version ushr 24) and 0xFF).toByte(),
            ((version ushr 16) and 0xFF).toByte(),
            ((version ushr 8) and 0xFF).toByte(),
            (version and 0xFF).toByte(),
        )
}
