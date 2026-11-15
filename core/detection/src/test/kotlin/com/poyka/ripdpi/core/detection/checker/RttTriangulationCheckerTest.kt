package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.RttTargetGroup
import com.poyka.ripdpi.core.detection.RttTriangulationState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RttTriangulationCheckerTest {
    @Test
    fun `no calls when disabled`() =
        runTest {
            val prober = FakePingProber()

            val result =
                RttTriangulationChecker.check(
                    prober = prober,
                    enabled = false,
                    homeCountryIso = "RU",
                )

            assertEquals(RttTriangulationState.SKIPPED, result.state)
            assertEquals(0, prober.calls.size)
            assertTrue(
                result.category.findings
                    .single()
                    .description
                    .contains("disabled"),
            )
        }

    @Test
    fun `skipped when home country is not russia`() =
        runTest {
            val prober = FakePingProber()

            val result =
                RttTriangulationChecker.check(
                    prober = prober,
                    enabled = true,
                    homeCountryIso = "DE",
                )

            assertEquals(RttTriangulationState.SKIPPED, result.state)
            assertEquals(0, prober.calls.size)
            assertTrue(
                result.category.findings
                    .single()
                    .description
                    .contains("home country DE"),
            )
        }

    @Test
    fun `needs review when ru median above threshold`() =
        runTest {
            val prober =
                FakePingProber(
                    replies =
                        mapOf(
                            "yandex.ru" to ping("yandex.ru", 100.0),
                            "mail.ru" to ping("mail.ru", 105.0),
                            "vk.com" to ping("vk.com", 95.0),
                            "sberbank.ru" to ping("sberbank.ru", 110.0),
                            "gosuslugi.ru" to ping("gosuslugi.ru", 100.0),
                            "facebook.com" to ping("facebook.com", 150.0),
                            "github.com" to ping("github.com", 145.0),
                            "twitter.com" to ping("twitter.com", 155.0),
                            "reddit.com" to ping("reddit.com", 150.0),
                            "instagram.com" to ping("instagram.com", 148.0),
                        ),
                )

            val result =
                RttTriangulationChecker.check(
                    prober = prober,
                    enabled = true,
                    homeCountryIso = "ru",
                )

            assertEquals(RttTriangulationState.NEEDS_REVIEW, result.state)
            assertEquals(
                EvidenceConfidence.MEDIUM,
                result.category.evidence
                    .single()
                    .confidence,
            )
            assertEquals(100.0, result.ruMedianRttMs!!, 0.1)
            assertEquals(10, result.targetResults.size)
            assertTrue(result.category.needsReview)
        }

    @Test
    fun `confidence downgraded when jitter above threshold on majority`() =
        runTest {
            val ruReplies = RttTriangulationChecker.RU_TARGETS.associateWith { ping(it, 120.0, jitterMs = 70.0) }
            val foreignReplies =
                RttTriangulationChecker.FOREIGN_TARGETS.associateWith {
                    ping(
                        it,
                        150.0,
                        jitterMs = 70.0,
                    )
                }
            val prober =
                FakePingProber(
                    replies = ruReplies + foreignReplies,
                )

            val result =
                RttTriangulationChecker.check(
                    prober = prober,
                    enabled = true,
                    homeCountryIso = "RU",
                )

            assertEquals(RttTriangulationState.NEEDS_REVIEW, result.state)
            assertEquals(
                EvidenceConfidence.LOW,
                result.category.evidence
                    .single()
                    .confidence,
            )
            assertTrue(
                result.targetResults.count {
                    it.jitterMs != null && it.jitterMs > 60.0
                } > result.targetResults.size / 2,
            )
        }

    @Test
    fun `confidence downgraded when foreign median is also high`() =
        runTest {
            val ruReplies = RttTriangulationChecker.RU_TARGETS.associateWith { ping(it, 120.0) }
            val foreignReplies = RttTriangulationChecker.FOREIGN_TARGETS.associateWith { ping(it, 210.0) }
            val prober =
                FakePingProber(
                    replies = ruReplies + foreignReplies,
                )

            val result =
                RttTriangulationChecker.check(
                    prober = prober,
                    enabled = true,
                    homeCountryIso = "RU",
                )

            assertEquals(RttTriangulationState.NEEDS_REVIEW, result.state)
            assertEquals(
                EvidenceConfidence.LOW,
                result.category.evidence
                    .single()
                    .confidence,
            )
        }

    @Test
    fun `no finding when ru median below threshold`() =
        runTest {
            val ruReplies = RttTriangulationChecker.RU_TARGETS.associateWith { ping(it, 30.0) }
            val foreignReplies = RttTriangulationChecker.FOREIGN_TARGETS.associateWith { ping(it, 120.0) }
            val prober =
                FakePingProber(
                    replies = ruReplies + foreignReplies,
                )

            val result =
                RttTriangulationChecker.check(
                    prober = prober,
                    enabled = true,
                    homeCountryIso = "RU",
                )

            assertEquals(RttTriangulationState.OK, result.state)
            assertFalse(result.category.needsReview)
            assertTrue(result.category.evidence.isEmpty())
            assertEquals(30.0, result.ruMedianRttMs!!, 0.1)
            assertEquals(5, result.targetResults.count { it.group == RttTargetGroup.RU && it.rttMs != null })
        }

    @Test
    fun `one surviving high jitter target has no supported cohort conclusion`() =
        runTest {
            val result =
                RttTriangulationChecker.check(
                    FakePingProber(mapOf("yandex.ru" to ping("yandex.ru", 120.0, 70.0))),
                    true,
                    "RU",
                )
            assertEquals(RttTriangulationState.INCONCLUSIVE, result.state)
            assertTrue(result.category.evidence.isEmpty())
            assertFalse(result.category.needsReview)
        }

    @Test
    fun `two targets per cohort are insufficient and three can support only low review`() =
        runTest {
            for (count in listOf(2, 3)) {
                val replies =
                    RttTriangulationChecker.RU_TARGETS.take(count).associateWith { ping(it, 120.0) } +
                        RttTriangulationChecker.FOREIGN_TARGETS.take(count).associateWith { ping(it, 150.0) }
                val result = RttTriangulationChecker.check(FakePingProber(replies), true, "RU")
                if (count == 2) {
                    assertEquals(RttTriangulationState.INCONCLUSIVE, result.state)
                    assertTrue(result.category.evidence.isEmpty())
                } else {
                    assertEquals(RttTriangulationState.NEEDS_REVIEW, result.state)
                    assertEquals(
                        EvidenceConfidence.LOW,
                        result.category.evidence
                            .single()
                            .confidence,
                    )
                }
            }
        }

    @Test
    fun `packet loss and unknown jitter reduce confidence of a complete target set`() =
        runTest {
            for (degraded in listOf(ping("yandex.ru", 120.0).copy(received = 1), ping("yandex.ru", 120.0, null))) {
                val replies =
                    RttTriangulationChecker.RU_TARGETS.associateWith { ping(it, 120.0) } +
                        RttTriangulationChecker.FOREIGN_TARGETS.associateWith { ping(it, 150.0) } +
                        mapOf("yandex.ru" to degraded)
                val result = RttTriangulationChecker.check(FakePingProber(replies), true, "RU")
                assertEquals(
                    EvidenceConfidence.LOW,
                    result.category.evidence
                        .single()
                        .confidence,
                )
            }
        }

    @Test
    fun `invalid and unreplied RTT values do not fill the cohort`() =
        runTest {
            val replies =
                RttTriangulationChecker.RU_TARGETS.associateWith { ping(it, Double.NaN) } +
                    RttTriangulationChecker.FOREIGN_TARGETS.associateWith { ping(it, 150.0).copy(replied = false) }
            val result = RttTriangulationChecker.check(FakePingProber(replies), true, "RU")
            assertEquals(RttTriangulationState.INCONCLUSIVE, result.state)
            assertEquals(null, result.ruMedianRttMs)
            assertEquals(null, result.foreignMedianRttMs)
            assertTrue(result.category.evidence.isEmpty())
        }

    private class FakePingProber(
        private val replies: Map<String, PingProbeResult> = emptyMap(),
    ) : PingProber {
        val calls = mutableListOf<String>()

        override suspend fun ping(host: String): PingProbeResult {
            calls += host
            return replies[host] ?: PingProbeResult.unreachable(host, resolvedIpv4 = null)
        }
    }

    private fun ping(
        host: String,
        rttMs: Double,
        jitterMs: Double? = 5.0,
    ): PingProbeResult =
        PingProbeResult.replied(
            host = host,
            resolvedIpv4 = "203.0.113.${host.length}",
            rttMs = rttMs,
            jitterMs = jitterMs,
            transmitted = 4,
            received = 4,
        )
}
