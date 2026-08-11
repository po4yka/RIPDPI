package com.poyka.ripdpi.diagnostics

import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeDiagnosticsAugmentationModuleTest {
    @Test
    fun `detected evidence is exported when no finding is flagged`() {
        val result =
            DetectionCheckResult(
                geoIp = emptyCategory("GeoIP"),
                directSigns = emptyCategory("Direct signs"),
                indirectSigns =
                    CategoryResult(
                        name = "Indirect signs",
                        detected = true,
                        findings = emptyList(),
                        evidence =
                            listOf(
                                EvidenceItem(
                                    source = EvidenceSource.DNS,
                                    detected = true,
                                    confidence = EvidenceConfidence.HIGH,
                                    description = "System resolver differs from the control resolver",
                                ),
                            ),
                    ),
                locationSignals = emptyCategory("Location"),
                bypassResult =
                    BypassResult(
                        proxyEndpoint = null,
                        directIp = null,
                        proxyIp = null,
                        xrayApiScanResult = null,
                        findings = emptyList(),
                        detected = false,
                    ),
                verdict = Verdict.DETECTED,
            )

        val outcome = requireNotNull(mapDetectionCheckResultToHomeDetectionStageOutcome(result))

        assertEquals(
            Triple(DiagnosticsHomeDetectionVerdict.DETECTED, 1, true),
            Triple(outcome.verdict, outcome.detectedSignalCount, outcome.findings.isNotEmpty()),
        )
    }

    @Test
    fun `detected verdict without supporting signals fails closed`() {
        val result =
            DetectionCheckResult(
                geoIp = emptyCategory("GeoIP"),
                directSigns = emptyCategory("Direct signs"),
                indirectSigns = emptyCategory("Indirect signs"),
                locationSignals = emptyCategory("Location"),
                bypassResult =
                    BypassResult(
                        proxyEndpoint = null,
                        directIp = null,
                        proxyIp = null,
                        xrayApiScanResult = null,
                        findings = emptyList(),
                        detected = false,
                    ),
                verdict = Verdict.DETECTED,
            )

        assertNull(mapDetectionCheckResultToHomeDetectionStageOutcome(result))
    }

    private fun emptyCategory(name: String): CategoryResult =
        CategoryResult(
            name = name,
            detected = false,
            findings = emptyList(),
        )
}
