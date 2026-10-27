package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.IcmpSpoofingState
import com.poyka.ripdpi.data.AppCoroutineDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IcmpSpoofingCheckerTest {
    private val pingParser =
        SystemPingProber(
            AppCoroutineDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined),
        )

    @Test
    fun `toybox ping output yields the average rtt and a jitter from the replies`() {
        val output =
            """
            PING 77.88.8.8 (77.88.8.8) 56(84) bytes of data.
            64 bytes from 77.88.8.8: icmp_seq=1 ttl=57 time=150 ms
            64 bytes from 77.88.8.8: icmp_seq=2 ttl=57 time=40 ms
            64 bytes from 77.88.8.8: icmp_seq=3 ttl=57 time=42 ms
            64 bytes from 77.88.8.8: icmp_seq=4 ttl=57 time=41 ms

            --- 77.88.8.8 ping statistics ---
            4 packets transmitted, 4 received, 0% packet loss, time 3004ms
            round-trip min/avg/max = 40/68/150 ms
            """.trimIndent()

        assertEquals(68.0, pingParser.parseRttMs(output)!!, 0.001)
        assertEquals(47.2037, pingParser.parseJitterMs(output)!!, 0.001)
    }

    @Test
    fun `iputils ping output yields the summary average and mdev`() {
        val output =
            """
            64 bytes from 77.88.8.8: icmp_seq=1 ttl=57 time=150.3 ms
            64 bytes from 77.88.8.8: icmp_seq=2 ttl=57 time=40.1 ms
            rtt min/avg/max/mdev = 40.100/95.200/150.300/55.100 ms
            """.trimIndent()

        assertEquals(95.2, pingParser.parseRttMs(output)!!, 0.001)
        assertEquals(55.1, pingParser.parseJitterMs(output)!!, 0.001)
    }

    @Test
    fun `reply from blocked target produces needs review finding`() =
        runTest {
            val result =
                IcmpSpoofingChecker.check(
                    prober =
                        FakePingProber(
                            "instagram.com" to PingProbeResult.replied("instagram.com", "31.13.72.174", 41.0),
                            "google.com" to PingProbeResult.unreachable("google.com", "142.250.185.206"),
                        ),
                    homeRoutedRoaming = false,
                )

            assertEquals(IcmpSpoofingState.NEEDS_REVIEW, result.state)
            assertTrue(result.category.needsReview)
            assertTrue(result.category.findings.any { it.description.contains("instagram.com replied") })
            assertEquals(
                EvidenceConfidence.MEDIUM,
                result.category.evidence
                    .single()
                    .confidence,
            )
        }

    @Test
    fun `control rtt below 10ms produces needs review finding`() =
        runTest {
            val result =
                IcmpSpoofingChecker.check(
                    prober =
                        FakePingProber(
                            "instagram.com" to PingProbeResult.unreachable("instagram.com", "31.13.72.174"),
                            "google.com" to PingProbeResult.replied("google.com", "142.250.185.206", 5.0),
                        ),
                    homeRoutedRoaming = false,
                )

            assertEquals(IcmpSpoofingState.NEEDS_REVIEW, result.state)
            assertTrue(result.category.needsReview)
            assertTrue(result.category.findings.any { it.description.contains("google.com RTT 5.0ms") })
            assertEquals(
                EvidenceConfidence.MEDIUM,
                result.category.evidence
                    .single()
                    .confidence,
            )
        }

    @Test
    fun `both conditions upgrade confidence to high`() =
        runTest {
            val result =
                IcmpSpoofingChecker.check(
                    prober =
                        FakePingProber(
                            "instagram.com" to PingProbeResult.replied("instagram.com", "31.13.72.174", 33.0),
                            "google.com" to PingProbeResult.replied("google.com", "142.250.185.206", 4.0),
                        ),
                    homeRoutedRoaming = false,
                )

            assertEquals(IcmpSpoofingState.NEEDS_REVIEW, result.state)
            assertTrue(result.category.needsReview)
            assertEquals(
                EvidenceConfidence.HIGH,
                result.category.evidence
                    .single()
                    .confidence,
            )
            assertTrue(result.category.findings.any { it.confidence == EvidenceConfidence.HIGH })
        }

    @Test
    fun `result skipped when home routed roaming true`() =
        runTest {
            val prober =
                FakePingProber(
                    "instagram.com" to PingProbeResult.replied("instagram.com", "31.13.72.174", 33.0),
                    "google.com" to PingProbeResult.replied("google.com", "142.250.185.206", 4.0),
                )

            val result =
                IcmpSpoofingChecker.check(
                    prober = prober,
                    homeRoutedRoaming = true,
                )

            assertEquals(IcmpSpoofingState.SKIPPED, result.state)
            assertFalse(result.category.needsReview)
            assertTrue(
                result.category.findings
                    .single()
                    .description
                    .contains("skipped"),
            )
            assertEquals(emptyList<String>(), prober.probedHosts)
        }

    private class FakePingProber(
        vararg results: Pair<String, PingProbeResult>,
    ) : PingProber {
        private val resultsByHost = results.toMap()
        val probedHosts = mutableListOf<String>()

        override suspend fun ping(host: String): PingProbeResult {
            probedHosts += host
            return requireNotNull(resultsByHost[host]) { "No fake ping result for $host" }
        }
    }
}
