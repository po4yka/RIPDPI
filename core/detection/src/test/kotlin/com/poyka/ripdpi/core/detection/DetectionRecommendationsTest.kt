package com.poyka.ripdpi.core.detection

import org.junit.Assert.assertEquals
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
    }

    @Test
    fun `transport VPN generates fingerprint recommendation`() {
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
        assertTrue(recs.any { it.actionRoute == "advanced_settings" })
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
    }

    @Test
    fun `local proxy generates full tunnel recommendation`() {
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
    }

    @Test
    fun `DNS loopback generates encrypted DNS recommendation`() {
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
        assertTrue(recs.any { it.actionRoute == "dns_settings" })
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
    }
}
