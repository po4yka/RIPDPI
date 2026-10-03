package com.poyka.ripdpi.core.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionRecommendationsTest {
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

    private fun emptyResult() =
        DetectionCheckResult(
            geoIp = emptyCategory("GeoIP"),
            directSigns = emptyCategory("Direct"),
            indirectSigns = emptyCategory("Indirect"),
            locationSignals = emptyCategory("Location"),
            bypassResult = emptyBypass(),
            verdict = Verdict.NOT_DETECTED,
        )

    @Test
    fun `no issues generates positive recommendation`() {
        val result = emptyResult()
        val recs = DetectionRecommendations.generate(result)
        assertEquals(1, recs.size)
        assertTrue(recs[0].title.contains("No issues"))
        assertNull(recs.single().destination)
    }

    @Test
    fun `transport VPN recommends a mode change instead of TLS changes`() {
        val result =
            emptyResult().copy(
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
                verdict = Verdict.DETECTED,
            )
        val recs = DetectionRecommendations.generate(result)
        assertTrue(recs.any { it.title.contains("VPN transport") })
        assertEquals(RecommendationDestination.MODE_SETTINGS, recs.single().destination)
        assertTrue(recs.none { it.description.contains("TLS") || it.description.contains("padding") })
    }

    @Test
    fun `xray API generates disable recommendation`() {
        val result =
            emptyResult().copy(
                bypassResult =
                    emptyBypass().copy(
                        evidence =
                            listOf(
                                EvidenceItem(
                                    source = EvidenceSource.XRAY_API,
                                    detected = true,
                                    confidence = EvidenceConfidence.HIGH,
                                    description = "Xray API found",
                                ),
                            ),
                    ),
                verdict = Verdict.DETECTED,
            )
        val recs = DetectionRecommendations.generate(result)
        assertTrue(recs.any { it.title.contains("Xray") })
        assertNull(recs.single().destination)
    }

    @Test
    fun `local proxy recommendation addresses the listener`() {
        val result =
            emptyResult().copy(
                bypassResult =
                    emptyBypass().copy(
                        evidence =
                            listOf(
                                EvidenceItem(
                                    source = EvidenceSource.LOCAL_PROXY,
                                    detected = true,
                                    confidence = EvidenceConfidence.MEDIUM,
                                    description = "SOCKS5 on 10808",
                                ),
                            ),
                    ),
                verdict = Verdict.NEEDS_REVIEW,
            )
        val recs = DetectionRecommendations.generate(result)
        assertTrue(recs.any { it.title.contains("localhost proxy") })
        assertEquals(RecommendationDestination.PROXY_SETTINGS, recs.single().destination)
        assertTrue(recs.none { it.description.contains("full tunnel") })
    }

    @Test
    fun `DNS loopback recommendation does not claim encrypted DNS hides it`() {
        val result =
            emptyResult().copy(
                indirectSigns =
                    emptyCategory("Indirect").copy(
                        evidence =
                            listOf(
                                EvidenceItem(
                                    source = EvidenceSource.DNS,
                                    detected = true,
                                    confidence = EvidenceConfidence.HIGH,
                                    description = "DNS loopback",
                                ),
                            ),
                    ),
                verdict = Verdict.NEEDS_REVIEW,
            )
        val recs = DetectionRecommendations.generate(result)
        assertTrue(recs.any { it.title.contains("DNS") })
        assertEquals(RecommendationDestination.DNS_SETTINGS, recs.single().destination)
        assertTrue(recs.none { it.description.contains("encrypted DNS") })
    }

    @Test
    fun `targeted app generates work profile recommendation`() {
        val result =
            emptyResult().copy(
                directSigns =
                    emptyCategory("Direct").copy(
                        matchedApps =
                            listOf(
                                MatchedVpnApp(
                                    packageName = "com.v2ray.ang",
                                    appName = "v2rayNG",
                                    family = "Xray",
                                    kind = VpnAppKind.TARGETED_BYPASS,
                                    source = EvidenceSource.INSTALLED_APP,
                                    active = false,
                                    confidence = EvidenceConfidence.MEDIUM,
                                ),
                            ),
                    ),
                verdict = Verdict.NEEDS_REVIEW,
            )
        val recs = DetectionRecommendations.generate(result)
        assertTrue(recs.any { it.title.contains("bypass app") })
        assertNull(recs.single().destination)
    }

    @Test
    fun `negative high confidence evidence does not recommend a remedy`() {
        val result =
            emptyResult().copy(
                directSigns =
                    emptyCategory("Direct").copy(
                        evidence = listOf(evidence(EvidenceSource.NETWORK_CAPABILITIES, detected = false)),
                    ),
                indirectSigns =
                    emptyCategory("Indirect").copy(
                        evidence = listOf(evidence(EvidenceSource.DNS, detected = false)),
                    ),
            )

        val recommendations = DetectionRecommendations.generate(result)

        assertEquals("No issues detected", recommendations.single().title)
        assertNull(recommendations.single().destination)
    }

    @Test
    fun `insufficient evidence does not offer a settings action`() {
        val result =
            emptyResult().copy(
                directSigns =
                    emptyCategory("Direct").copy(
                        evidence =
                            listOf(
                                evidence(EvidenceSource.NETWORK_CAPABILITIES).copy(confidence = EvidenceConfidence.LOW),
                            ),
                    ),
                indirectSigns =
                    emptyCategory("Indirect").copy(
                        evidence = listOf(evidence(EvidenceSource.DNS).copy(confidence = EvidenceConfidence.LOW)),
                    ),
                verdict = Verdict.NEEDS_REVIEW,
            )

        assertTrue(DetectionRecommendations.generate(result).isEmpty())
    }

    @Test
    fun `unrelated network observations do not invent universal traffic fixes`() {
        val result =
            emptyResult().copy(
                indirectSigns =
                    emptyCategory("Indirect").copy(
                        evidence =
                            listOf(
                                evidence(EvidenceSource.RTT_TRIANGULATION),
                                evidence(EvidenceSource.CDN_PULLING),
                            ),
                    ),
                verdict = Verdict.NEEDS_REVIEW,
            )

        assertTrue(DetectionRecommendations.generate(result).isEmpty())
    }

    private fun evidence(
        source: EvidenceSource,
        detected: Boolean = true,
    ): EvidenceItem =
        EvidenceItem(
            source = source,
            detected = detected,
            confidence = EvidenceConfidence.HIGH,
            description = "Observed test evidence",
        )
}
