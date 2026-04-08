package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.core.detection.VpnAppKind
import org.junit.Assert.assertEquals
import org.junit.Test

class VerdictEngineTest {
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

    @Test
    fun `no evidence returns NOT_DETECTED`() {
        val verdict =
            VerdictEngine.evaluate(
                geoIp = emptyCategory("GeoIP"),
                directSigns = emptyCategory("Direct"),
                indirectSigns = emptyCategory("Indirect"),
                locationSignals = emptyCategory("Location"),
                bypassResult = emptyBypass(),
            )
        assertEquals(Verdict.NOT_DETECTED, verdict)
    }

    @Test
    fun `split tunnel bypass returns DETECTED`() {
        val bypass =
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
            )
        val verdict =
            VerdictEngine.evaluate(
                geoIp = emptyCategory("GeoIP"),
                directSigns = emptyCategory("Direct"),
                indirectSigns = emptyCategory("Indirect"),
                locationSignals = emptyCategory("Location"),
                bypassResult = bypass,
            )
        assertEquals(Verdict.DETECTED, verdict)
    }

    @Test
    fun `xray API detected returns DETECTED`() {
        val bypass =
            emptyBypass().copy(
                evidence =
                    listOf(
                        EvidenceItem(
                            source = EvidenceSource.XRAY_API,
                            detected = true,
                            confidence = EvidenceConfidence.HIGH,
                            description = "Xray found",
                        ),
                    ),
            )
        val verdict =
            VerdictEngine.evaluate(
                geoIp = emptyCategory("GeoIP"),
                directSigns = emptyCategory("Direct"),
                indirectSigns = emptyCategory("Indirect"),
                locationSignals = emptyCategory("Location"),
                bypassResult = bypass,
            )
        assertEquals(Verdict.DETECTED, verdict)
    }

    @Test
    fun `generic VPN active returns NEEDS_REVIEW`() {
        val indirect =
            emptyCategory("Indirect").copy(
                evidence =
                    listOf(
                        EvidenceItem(
                            source = EvidenceSource.ACTIVE_VPN,
                            detected = true,
                            confidence = EvidenceConfidence.MEDIUM,
                            description = "WireGuard",
                            kind = VpnAppKind.GENERIC_VPN,
                        ),
                    ),
            )
        val verdict =
            VerdictEngine.evaluate(
                geoIp = emptyCategory("GeoIP"),
                directSigns = emptyCategory("Direct"),
                indirectSigns = indirect,
                locationSignals = emptyCategory("Location"),
                bypassResult = emptyBypass(),
            )
        assertEquals(Verdict.NEEDS_REVIEW, verdict)
    }

    @Test
    fun `Russian MCC with foreign GeoIP returns DETECTED`() {
        val location =
            emptyCategory("Location").copy(
                findings = listOf(Finding("network_mcc_ru:true")),
            )
        val geoIp = emptyCategory("GeoIP").copy(needsReview = true)
        val verdict =
            VerdictEngine.evaluate(
                geoIp = geoIp,
                directSigns = emptyCategory("Direct"),
                indirectSigns = emptyCategory("Indirect"),
                locationSignals = location,
                bypassResult = emptyBypass(),
            )
        assertEquals(Verdict.DETECTED, verdict)
    }

    @Test
    fun `targeted active with local proxy returns DETECTED`() {
        val indirect =
            emptyCategory("Indirect").copy(
                evidence =
                    listOf(
                        EvidenceItem(
                            source = EvidenceSource.ACTIVE_VPN,
                            detected = true,
                            confidence = EvidenceConfidence.HIGH,
                            description = "v2rayNG active",
                            kind = VpnAppKind.TARGETED_BYPASS,
                        ),
                    ),
            )
        val bypass =
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
            )
        val verdict =
            VerdictEngine.evaluate(
                geoIp = emptyCategory("GeoIP"),
                directSigns = emptyCategory("Direct"),
                indirectSigns = indirect,
                locationSignals = emptyCategory("Location"),
                bypassResult = bypass,
            )
        assertEquals(Verdict.DETECTED, verdict)
    }

    @Test
    fun `score at 4 returns NEEDS_REVIEW`() {
        val direct =
            emptyCategory("Direct").copy(
                evidence =
                    listOf(
                        EvidenceItem(
                            source = EvidenceSource.SYSTEM_PROXY,
                            detected = true,
                            confidence = EvidenceConfidence.MEDIUM,
                            description = "HTTP proxy configured",
                        ),
                        EvidenceItem(
                            source = EvidenceSource.INSTALLED_APP,
                            detected = true,
                            confidence = EvidenceConfidence.LOW,
                            description = "Generic VPN installed",
                            kind = VpnAppKind.GENERIC_VPN,
                        ),
                    ),
            )
        val verdict =
            VerdictEngine.evaluate(
                geoIp = emptyCategory("GeoIP"),
                directSigns = direct,
                indirectSigns = emptyCategory("Indirect"),
                locationSignals = emptyCategory("Location"),
                bypassResult = emptyBypass(),
            )
        assertEquals(Verdict.NEEDS_REVIEW, verdict)
    }
}
