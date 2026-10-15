package com.poyka.ripdpi.activities

import com.poyka.ripdpi.core.detection.DetectionScope
import com.poyka.ripdpi.diagnostics.DiagnosticsAppliedSetting
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageStatus
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageSummary
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeDetectionVerdict
import com.poyka.ripdpi.diagnostics.HomeBufferbloatGrade
import com.poyka.ripdpi.diagnostics.HomeBufferbloatResult
import com.poyka.ripdpi.diagnostics.HomeDnsCharacterization
import com.poyka.ripdpi.diagnostics.HomeDnsResolverClass
import com.poyka.ripdpi.diagnostics.HomeRegressionDelta
import com.poyka.ripdpi.diagnostics.HomeStrategyEffectivenessEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeActionablePresentationTest {
    @Test
    fun `generated headline and highest priority four steps localize without mutating source or payloads`() {
        val rawFinding = "raw.finding: VPN · custom. Signal"
        val rawSteps =
            listOf(
                "Detection signal: $rawFinding",
                "Add per-app routing for: org.example.one, org.example.two",
                "Failed: custom.stage",
                "High bufferbloat — consider QoS or a different relay transport",
            )
        val outcome =
            homeOutcome().copy(
                detectionVerdict = DiagnosticsHomeDetectionVerdict.DETECTED,
                detectionEvidenceScopes = listOf(DetectionScope.NETWORK_OBSERVATION),
                detectionFindings = listOf(rawFinding),
                installedVpnDetectorCount = 2,
                installedVpnDetectorTopApps = listOf("org.example.one", "org.example.two", "org.hidden"),
                appliedSettings = listOf(DiagnosticsAppliedSetting("Resolver", "raw DNS")),
                failedStageCount = 1,
                stageSummaries =
                    listOf(
                        DiagnosticsHomeCompositeStageSummary(
                            stageKey = "custom_key",
                            stageLabel = "custom.stage",
                            profileId = "custom",
                            pathMode = null,
                            status = DiagnosticsHomeCompositeStageStatus.FAILED,
                            headline = "Custom",
                            summary = "raw",
                        ),
                    ),
                bufferbloat = HomeBufferbloatResult(HomeBufferbloatGrade.D),
                actionableHeadline =
                    "Network-observed VPN detection pattern requires review. " +
                        "2 VPN-detector apps installed. Applied 1 DNS setting.",
                actionableNextSteps = rawSteps,
            )
        val english = projectHome(outcome, "en").analysisSheet!!
        val arabic = projectHome(outcome, "ar").analysisSheet!!
        assertArabic(arabic.actionableHeadline)
        assertEquals(4, arabic.actionableNextSteps.size)
        arabic.actionableNextSteps.forEach(::assertArabic)
        assertTrue(arabic.actionableNextSteps[0].contains(rawFinding))
        assertTrue(arabic.actionableNextSteps[1].contains("org.example.one, org.example.two"))
        assertTrue(arabic.actionableNextSteps[2].contains("custom.stage"))
        assertEquals(english, projectHome(outcome, "en").analysisSheet)
        assertEquals(rawSteps, outcome.actionableNextSteps)
        assertEquals(rawSteps, english.actionableNextSteps)
    }

    @Test
    fun `lower priority generated steps keep DNS warning regression keys and historical edge raw`() {
        val edge = "example.org:443 · UDP (raw / edge)"
        val rawSteps =
            listOf(
                "Encrypted DNS endpoint unreachable — check connectivity and retry",
                "Regression: dns, custom.stage stopped working since the previous run",
                "Best historical edge on this network: $edge (3 pass / 1 fail)",
                "Custom next step",
            )
        val outcome =
            homeOutcome().copy(
                actionableHeadline = "Analysis complete and bypass applied.",
                actionableNextSteps = rawSteps,
                dnsCharacterization = HomeDnsCharacterization(HomeDnsResolverClass.DOH_UNREACHABLE),
                regressionDelta = HomeRegressionDelta("previous", listOf("dns", "custom.stage")),
                strategyEffectiveness = listOf(HomeStrategyEffectivenessEntry(edge, 3, 1)),
            )
        val arabic = projectHome(outcome, "ar").analysisSheet!!
        assertArabic(arabic.actionableHeadline)
        arabic.actionableNextSteps.take(3).forEach(::assertArabic)
        assertTrue(arabic.actionableNextSteps[1].contains("dns, custom.stage"))
        assertTrue(arabic.actionableNextSteps[2].contains(edge))
        assertEquals("Custom next step", arabic.actionableNextSteps.last())
        assertEquals(rawSteps, projectHome(outcome, "en").analysisSheet!!.actionableNextSteps)
    }

    @Test
    fun `custom and legacy text that resembles a generated prefix stays exact`() {
        val outcome =
            homeOutcome().copy(
                detectionVerdict = DiagnosticsHomeDetectionVerdict.NEEDS_REVIEW,
                actionableHeadline = "Some detection checks need review (custom reason)",
                actionableNextSteps = listOf("Detection signal: custom unknown", "Applied 10 DNS settings", "Custom"),
            )
        val sheet = projectHome(outcome, "ar").analysisSheet!!
        assertEquals(outcome.actionableHeadline, sheet.actionableHeadline)
        assertEquals(outcome.actionableNextSteps, sheet.actionableNextSteps)
    }

    @Test
    fun `local detector review and bypass singular and plural headlines remain distinct`() {
        val local =
            homeOutcome().copy(
                detectionVerdict = DiagnosticsHomeDetectionVerdict.DETECTED,
                actionableHeadline = "Local observer found VPN-related artifacts.",
            )
        assertArabic(projectHome(local, "ar").analysisSheet!!.actionableHeadline)
        val review =
            homeOutcome().copy(
                detectionVerdict = DiagnosticsHomeDetectionVerdict.NEEDS_REVIEW,
                actionableHeadline = "Some detection checks need review (likely missing permissions).",
            )
        assertArabic(projectHome(review, "ar").analysisSheet!!.actionableHeadline)
        listOf(1, 2).forEach { count ->
            val settings = List(count) { DiagnosticsAppliedSetting("Custom bypass", "raw") }
            val suffix = if (count == 1) "" else "s"
            val outcome =
                homeOutcome().copy(
                    appliedSettings = settings,
                    actionableHeadline = "Applied $count bypass setting$suffix.",
                )
            assertArabic(projectHome(outcome, "ar").analysisSheet!!.actionableHeadline)
            assertEquals(outcome.actionableHeadline, projectHome(outcome, "en").analysisSheet!!.actionableHeadline)
        }
    }

    @Test
    fun `Arabic detector and applied-setting counts select singular dual few many and other categories`() {
        listOf(1, 2, 3, 11, 100, 102).forEach { count ->
            val plural = if (count == 1) "" else "s"
            val detector =
                homeOutcome().copy(
                    installedVpnDetectorCount = count,
                    actionableHeadline = "$count VPN-detector app$plural installed.",
                )
            assertArabic(projectHome(detector, "ar").analysisSheet!!.actionableHeadline)
            assertEquals(detector.actionableHeadline, projectHome(detector, "en").analysisSheet!!.actionableHeadline)
            listOf("Resolver" to "DNS", "Custom" to "bypass").forEach { (label, kind) ->
                val outcome =
                    homeOutcome().copy(
                        appliedSettings = List(count) { DiagnosticsAppliedSetting(label, "raw") },
                        actionableHeadline = "Applied $count $kind setting$plural.",
                    )
                assertArabic(projectHome(outcome, "ar").analysisSheet!!.actionableHeadline)
                assertEquals(outcome.actionableHeadline, projectHome(outcome, "en").analysisSheet!!.actionableHeadline)
            }
        }
        val zero = homeOutcome().copy(installedVpnDetectorCount = 0, actionableHeadline = "Custom")
        assertEquals("Custom", projectHome(zero, "ar").analysisSheet!!.actionableHeadline)
    }
}
