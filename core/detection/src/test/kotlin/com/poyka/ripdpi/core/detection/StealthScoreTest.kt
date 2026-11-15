package com.poyka.ripdpi.core.detection

import com.poyka.ripdpi.core.detection.checker.TimingAnalysisChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StealthScoreTest {
    private fun emptyCategory(name: String) =
        CategoryResult(
            name = name,
            detected = false,
            findings = emptyList(),
        )

    private fun emptyBypass() =
        BypassResult(
            proxyEndpoint = null,
            directIp = null,
            proxyIp = null,
            xrayApiScanResult = null,
            findings = emptyList(),
            detected = false,
        )

    private fun cleanResult() =
        DetectionCheckResult(
            geoIp = emptyCategory("GeoIP"),
            directSigns = emptyCategory("Direct"),
            indirectSigns = emptyCategory("Indirect"),
            locationSignals = emptyCategory("Location"),
            bypassResult = emptyBypass(),
            verdict = Verdict.NOT_DETECTED,
        )

    @Test
    fun `clean result scores 100`() {
        assertEquals(100, StealthScore.compute(cleanResult()))
    }

    @Test
    fun `name only candidate leaves score and traffic claims unchanged`() {
        val candidate =
            MatchedVpnApp(
                packageName = "org.example.candidate",
                appName = "VPN glossary",
                family = null,
                kind = VpnAppKind.GENERIC_VPN,
                source = EvidenceSource.INSTALLED_APP,
                active = false,
                confidence = EvidenceConfidence.LOW,
                technicalMetadata = VpnAppTechnicalMetadata(matchedByNameHeuristic = true),
            )
        val result =
            cleanResult().copy(
                directSigns = emptyCategory("Direct").copy(needsReview = true, matchedApps = listOf(candidate)),
                verdict = Verdict.NEEDS_REVIEW,
            )

        assertEquals(100, StealthScore.compute(result))
        assertEquals("Additional diagnostic signals need review", ExposureVerdictBuilder.build(result, 100).headline)
        val known =
            result.copy(
                directSigns = result.directSigns.copy(matchedApps = listOf(candidate.copy(technicalMetadata = null))),
            )
        assertEquals(97, StealthScore.compute(known))
    }

    @Test
    fun `split bypass reduces score significantly`() {
        val result =
            cleanResult().copy(
                bypassResult =
                    emptyBypass().copy(
                        evidence =
                            listOf(
                                EvidenceItem(
                                    source = EvidenceSource.SPLIT_TUNNEL_BYPASS,
                                    detected = true,
                                    confidence = EvidenceConfidence.HIGH,
                                    description = "IPs differ",
                                ),
                            ),
                    ),
            )
        assertTrue(StealthScore.compute(result) <= 60)
    }

    @Test
    fun `transport VPN reduces score by 20`() {
        val result =
            cleanResult().copy(
                directSigns =
                    emptyCategory("Direct").copy(
                        evidence =
                            listOf(
                                EvidenceItem(
                                    source = EvidenceSource.NETWORK_CAPABILITIES,
                                    detected = true,
                                    confidence = EvidenceConfidence.HIGH,
                                    description = "TRANSPORT_VPN",
                                ),
                            ),
                    ),
            )
        assertEquals(80, StealthScore.compute(result))
        assertEquals(
            "Android VPN binding",
            ExposureVerdictBuilder.build(result, stealthScore = 80).dominantSignal,
        )
    }

    @Test
    fun `network timing remains a timing observation at low and medium confidence`() {
        for (confidence in listOf(EvidenceConfidence.LOW, EvidenceConfidence.MEDIUM)) {
            val result =
                cleanResult().copy(
                    timingAnalysis =
                        emptyCategory("Timing Analysis").copy(
                            needsReview = true,
                            evidence =
                                listOf(
                                    TimingAnalysisChecker.networkTimingEvidence(
                                        confidence = confidence,
                                        description = "Similar TCP connection latency",
                                    ),
                                ),
                        ),
                )

            val verdict = ExposureVerdictBuilder.build(result, StealthScore.compute(result))

            assertEquals("Additional diagnostic signals need review", verdict.headline)
            assertEquals("timing anomaly", verdict.dominantSignal)
            assertEquals(
                "Latency or timing patterns differ from ordinary direct HTTPS traffic.",
                verdict.reason,
            )
        }
    }

    @Test
    fun `local VPN capability remains distinct from network timing`() {
        val result =
            cleanResult().copy(
                directSigns =
                    emptyCategory("Direct").copy(
                        evidence =
                            listOf(
                                EvidenceItem(
                                    source = EvidenceSource.NETWORK_CAPABILITIES,
                                    detected = true,
                                    confidence = EvidenceConfidence.HIGH,
                                    description = "TRANSPORT_VPN",
                                ),
                            ),
                    ),
                timingAnalysis =
                    emptyCategory("Timing Analysis").copy(
                        evidence =
                            listOf(
                                TimingAnalysisChecker.networkTimingEvidence(
                                    confidence = EvidenceConfidence.LOW,
                                    description = "Similar TCP connection latency",
                                ),
                            ),
                    ),
            )

        val verdict = ExposureVerdictBuilder.build(result, StealthScore.compute(result))

        assertEquals("Android VPN binding", verdict.dominantSignal)
        assertEquals("Android network capabilities expose VPN routing to local observers.", verdict.reason)
    }

    @Test
    fun `score clamped to 0`() {
        val result =
            cleanResult().copy(
                bypassResult =
                    emptyBypass().copy(
                        evidence =
                            listOf(
                                EvidenceItem(
                                    source = EvidenceSource.SPLIT_TUNNEL_BYPASS,
                                    detected = true,
                                    confidence = EvidenceConfidence.HIGH,
                                    description = "IPs differ",
                                ),
                                EvidenceItem(
                                    source = EvidenceSource.XRAY_API,
                                    detected = true,
                                    confidence = EvidenceConfidence.HIGH,
                                    description = "Xray",
                                ),
                                EvidenceItem(
                                    source = EvidenceSource.LOCAL_PROXY,
                                    detected = true,
                                    confidence = EvidenceConfidence.MEDIUM,
                                    description = "SOCKS5",
                                ),
                            ),
                    ),
                directSigns =
                    emptyCategory("Direct").copy(
                        evidence =
                            listOf(
                                EvidenceItem(
                                    source = EvidenceSource.NETWORK_CAPABILITIES,
                                    detected = true,
                                    confidence = EvidenceConfidence.HIGH,
                                    description = "TRANSPORT_VPN",
                                ),
                            ),
                    ),
            )
        assertTrue(StealthScore.compute(result) >= 0)
    }

    @Test
    fun `label returns correct values`() {
        assertEquals("Excellent", StealthScore.label(95))
        assertEquals("Good", StealthScore.label(75))
        assertEquals("Fair", StealthScore.label(55))
        assertEquals("Poor", StealthScore.label(35))
        assertEquals("Critical", StealthScore.label(10))
    }

    @Test
    fun `normalizedProgress is correct`() {
        assertEquals(1f, StealthScore.normalizedProgress(100), 0.01f)
        assertEquals(0.5f, StealthScore.normalizedProgress(50), 0.01f)
        assertEquals(0f, StealthScore.normalizedProgress(0), 0.01f)
    }

    @Test
    fun `exposure verdict names ordinary HTTPS when probes are clean`() {
        val verdict = ExposureVerdictBuilder.build(cleanResult(), stealthScore = 100)

        assertEquals("You look like ordinary HTTPS traffic", verdict.headline)
        assertEquals("ordinary HTTPS baseline", verdict.dominantSignal)
        assertTrue(verdict.reason.contains("Detection probes did not expose"))
    }

    @Test
    fun `missing ICMP RTT or TCP measurements do not claim a clean result`() {
        for (status in listOf(CheckExecutionStatus.INCONCLUSIVE, CheckExecutionStatus.ERROR)) {
            val unavailable = emptyCategory("Network check").copy(executionStatus = status)
            val results =
                listOf(
                    cleanResult().copy(timingAnalysis = unavailable),
                    cleanResult().copy(
                        icmpSpoofing = IcmpSpoofingResult(IcmpSpoofingState.INCONCLUSIVE, unavailable),
                    ),
                    cleanResult().copy(
                        rttTriangulation = RttTriangulationResult(RttTriangulationState.INCONCLUSIVE, unavailable),
                    ),
                )

            for (result in results) {
                val verdict = ExposureVerdictBuilder.build(result, stealthScore = 100)

                assertEquals("Insufficient evidence", verdict.headline)
                assertEquals("Some checks did not return a result.", verdict.reason)
                assertEquals("Review the details and run the check again.", verdict.adjustmentHint)
            }
        }
    }

    @Test
    fun `skipped network check does not imply missing required measurements`() {
        val result =
            cleanResult().copy(
                rttTriangulation =
                    RttTriangulationResult(
                        RttTriangulationState.SKIPPED,
                        emptyCategory("RTT").copy(executionStatus = CheckExecutionStatus.SKIPPED),
                    ),
            )

        assertEquals(
            "You look like ordinary HTTPS traffic",
            ExposureVerdictBuilder.build(result, stealthScore = 100).headline,
        )
    }

    @Test
    fun `missing timing measurements do not hide a confirmed local VPN signal`() {
        val result =
            cleanResult().copy(
                timingAnalysis = emptyCategory("Timing").copy(executionStatus = CheckExecutionStatus.ERROR),
                directSigns =
                    emptyCategory("Direct").copy(
                        detected = true,
                        evidence =
                            listOf(
                                EvidenceItem(
                                    source = EvidenceSource.NETWORK_CAPABILITIES,
                                    detected = true,
                                    confidence = EvidenceConfidence.HIGH,
                                    description = "TRANSPORT_VPN",
                                ),
                            ),
                    ),
                verdict = Verdict.DETECTED,
            )

        val verdict = ExposureVerdictBuilder.build(result, stealthScore = 80)

        assertEquals("Your traffic is distinguishable by Android VPN binding", verdict.headline)
        assertEquals("Android VPN binding", verdict.dominantSignal)
    }

    @Test
    fun `unscored diagnostic signal does not get clean exposure copy`() {
        val result =
            cleanResult().copy(
                tlsFingerprint = emptyCategory("TLS").copy(needsReview = true),
            )

        val verdict = ExposureVerdictBuilder.build(result, stealthScore = 100)

        assertEquals("Additional diagnostic signals need review", verdict.headline)
        assertEquals("Local TLS settings", verdict.dominantSignal)
        assertTrue(VerdictNarrativeBuilder.build(result).reasonRows.any { it.label == "Local TLS settings" })
    }

    @Test
    fun `failed local TLS read is incomplete without VPN attribution`() {
        val result =
            cleanResult().copy(
                tlsFingerprint = emptyCategory("Local TLS settings").copy(executionStatus = CheckExecutionStatus.ERROR),
            )
        assertTrue(result.hasIncompleteChecks())
        assertEquals("Insufficient evidence", ExposureVerdictBuilder.build(result, 100).headline)
    }

    @Test
    fun `exposure verdict prioritizes detection outcome over good stealth score`() {
        val result =
            cleanResult().copy(
                bypassResult =
                    emptyBypass().copy(
                        evidence =
                            listOf(
                                EvidenceItem(
                                    source = EvidenceSource.XRAY_API,
                                    detected = true,
                                    confidence = EvidenceConfidence.HIGH,
                                    description = "Xray API endpoint reachable",
                                ),
                            ),
                        detected = true,
                    ),
                verdict = Verdict.DETECTED,
            )

        val verdict = ExposureVerdictBuilder.build(result, stealthScore = 95)

        assertEquals("Your traffic is distinguishable by Xray API access", verdict.headline)
        assertEquals("Xray API access", verdict.dominantSignal)
        assertTrue(verdict.reason.contains("local API"))
        assertTrue(verdict.adjustmentHint.contains("Restrict local API"))
    }
}
