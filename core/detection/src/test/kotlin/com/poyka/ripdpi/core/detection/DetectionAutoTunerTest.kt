package com.poyka.ripdpi.core.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionAutoTunerTest {
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
    fun `clean result with all enabled suggests nothing`() {
        val fixes =
            DetectionAutoTuner.suggestFixes(
                result = cleanResult(),
                tlsFingerprintEnabled = true,
                entropyPaddingEnabled = true,
                encryptedDnsEnabled = true,
                fullTunnelEnabled = true,
                strategyEvolutionEnabled = true,
            )
        assertTrue(fixes.isEmpty())
    }

    @Test
    fun `transport VPN with no fingerprint suggests tls and entropy`() {
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
                verdict = Verdict.DETECTED,
            )
        val fixes = DetectionAutoTuner.suggestFixes(result = result)
        assertTrue(fixes.any { it.id == "tls_fingerprint" })
        assertTrue(fixes.any { it.id == "entropy_padding" })
    }

    @Test
    fun `dns loopback suggests encrypted dns`() {
        val result =
            cleanResult().copy(
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
        val fixes = DetectionAutoTuner.suggestFixes(result = result)
        assertTrue(fixes.any { it.id == "encrypted_dns" })
    }

    @Test
    fun `local proxy suggests full tunnel`() {
        val result =
            cleanResult().copy(
                bypassResult =
                    emptyBypass().copy(
                        evidence =
                            listOf(
                                EvidenceItem(
                                    source = EvidenceSource.LOCAL_PROXY,
                                    detected = true,
                                    confidence = EvidenceConfidence.MEDIUM,
                                    description = "SOCKS5",
                                ),
                            ),
                    ),
                verdict = Verdict.NEEDS_REVIEW,
            )
        val fixes = DetectionAutoTuner.suggestFixes(result = result)
        assertTrue(fixes.any { it.id == "full_tunnel" })
    }

    @Test
    fun `already enabled features not suggested`() {
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
                verdict = Verdict.DETECTED,
            )
        val fixes =
            DetectionAutoTuner.suggestFixes(
                result = result,
                tlsFingerprintEnabled = true,
                entropyPaddingEnabled = true,
            )
        assertEquals(0, fixes.count { it.id == "tls_fingerprint" })
        assertEquals(0, fixes.count { it.id == "entropy_padding" })
    }
}
