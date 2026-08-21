package com.poyka.ripdpi.core.detection.checker

import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DecisionSignalCategory
import com.poyka.ripdpi.core.detection.DecisionSignalSemantics
import com.poyka.ripdpi.core.detection.DetectionScope
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.core.detection.VpnAppKind
import org.junit.Assert.assertEquals
import org.junit.Test

class VerdictDecisionSignalsTest {
    @Test
    fun `inventory evidence reports one scoped logical signal`() {
        val direct =
            emptyCategory("Direct").copy(
                needsReview = true,
                evidence =
                    listOf(
                        EvidenceItem(
                            source = EvidenceSource.INSTALLED_APP,
                            detected = true,
                            confidence = EvidenceConfidence.MEDIUM,
                            description = "Targeted bypass app installed",
                            family = "xray",
                            packageName = "com.example.targeted",
                            kind = VpnAppKind.TARGETED_BYPASS,
                        ),
                        EvidenceItem(
                            source = EvidenceSource.VPN_SERVICE_DECLARATION,
                            detected = true,
                            confidence = EvidenceConfidence.MEDIUM,
                            description = "Targeted bypass app declares VpnService",
                            family = "xray",
                            packageName = "com.example.targeted",
                            kind = VpnAppKind.TARGETED_BYPASS,
                        ),
                    ),
            )

        val explanation =
            VerdictEngine.evaluateDetailed(
                geoIp = emptyCategory("GeoIP"),
                directSigns = direct,
                indirectSigns = emptyCategory("Indirect"),
                locationSignals = emptyCategory("Location"),
                bypassResult = emptyBypass(),
            )

        assertEquals(
            listOf(
                Verdict.NEEDS_REVIEW,
                "R6",
                "1 local inventory match requires review",
                listOf(DetectionScope.LOCAL_INVENTORY),
                1,
                listOf(
                    listOf(
                        DecisionSignalCategory.LOCAL_INVENTORY_REVIEW,
                        DecisionSignalSemantics.LOCAL_INVENTORY_MATCH_REQUIRES_REVIEW,
                        EvidenceSource.INSTALLED_APP,
                        EvidenceConfidence.MEDIUM,
                        DetectionScope.LOCAL_INVENTORY,
                    ),
                ),
            ),
            listOf(
                explanation.verdict,
                explanation.ruleApplied,
                explanation.summary,
                explanation.appliedScopes,
                explanation.uniqueSignalCount,
                explanation.decisionSignals.map {
                    listOf(it.category, it.semantics, it.source, it.confidence, it.scope)
                },
            ),
        )
    }

    @Test
    fun `five inventory matches preserve R6 signal count without package identifiers`() {
        val direct =
            emptyCategory("Direct").copy(
                needsReview = true,
                evidence =
                    List(5) { index ->
                        EvidenceItem(
                            source = EvidenceSource.INSTALLED_APP,
                            detected = true,
                            confidence = EvidenceConfidence.MEDIUM,
                            description = "Targeted bypass app installed",
                            family = "family-$index",
                            packageName = "com.example.targeted$index",
                            kind = VpnAppKind.TARGETED_BYPASS,
                        )
                    },
            )

        val explanation =
            VerdictEngine.evaluateDetailed(
                geoIp = emptyCategory("GeoIP"),
                directSigns = direct,
                indirectSigns = emptyCategory("Indirect"),
                locationSignals = emptyCategory("Location"),
                bypassResult = emptyBypass(),
            )

        assertEquals(Verdict.NEEDS_REVIEW, explanation.verdict)
        assertEquals("R6", explanation.ruleApplied)
        assertEquals(5, explanation.uniqueSignalCount)
        assertEquals(5, explanation.decisionSignals.size)
        assertEquals(
            setOf(DecisionSignalCategory.LOCAL_INVENTORY_REVIEW),
            explanation.decisionSignals.map { it.category }.toSet(),
        )
    }

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
}
