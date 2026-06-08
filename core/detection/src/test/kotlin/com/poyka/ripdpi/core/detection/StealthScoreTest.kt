package com.poyka.ripdpi.core.detection

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
}
