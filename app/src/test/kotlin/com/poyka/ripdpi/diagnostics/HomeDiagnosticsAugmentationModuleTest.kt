package com.poyka.ripdpi.diagnostics

import android.content.Context
import com.poyka.ripdpi.activities.FakeAppSettingsRepository
import com.poyka.ripdpi.core.detection.BypassResult
import com.poyka.ripdpi.core.detection.CategoryResult
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionCheckRunner
import com.poyka.ripdpi.core.detection.DetectionRunnerConfig
import com.poyka.ripdpi.core.detection.DetectionScope
import com.poyka.ripdpi.core.detection.EvidenceConfidence
import com.poyka.ripdpi.core.detection.EvidenceItem
import com.poyka.ripdpi.core.detection.EvidenceSource
import com.poyka.ripdpi.core.detection.Finding
import com.poyka.ripdpi.core.detection.Verdict
import com.poyka.ripdpi.core.detection.VerdictExplanation
import com.poyka.ripdpi.core.detection.VpnAppKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HomeDiagnosticsAugmentationModuleTest {
    @Test
    fun `inventory-only detection maps to one review signal without network claim`() =
        runTest {
            val runner = detectionStageRunnerWith(inventoryOnlyDetectionResult())

            val outcome = runner.run { _, _ -> }

            assertEquals(
                listOf(
                    DiagnosticsHomeDetectionVerdict.NEEDS_REVIEW,
                    1,
                    true,
                    listOf(DetectionScope.LOCAL_INVENTORY),
                    false,
                ),
                listOf(
                    outcome?.verdict,
                    outcome?.detectedSignalCount,
                    outcome?.ruleApplied?.isNotBlank(),
                    outcome?.evidenceScopes,
                    outcome?.findings?.any { it.contains("on this network", ignoreCase = true) },
                ),
            )
        }

    private fun inventoryOnlyDetectionResult(): DetectionCheckResult {
        val packageName = "com.example.targeted"
        val family = "xray"
        val installedApp = targetedBypassEvidence(EvidenceSource.INSTALLED_APP, packageName, family)
        val vpnServiceDeclaration = targetedBypassEvidence(EvidenceSource.VPN_SERVICE_DECLARATION, packageName, family)
        val directSigns =
            CategoryResult(
                name = "Direct signs",
                detected = false,
                needsReview = true,
                findings =
                    listOf(
                        Finding("Targeted bypass app installed", detected = true),
                        Finding("Targeted bypass app declares VpnService", detected = true),
                    ),
                evidence = listOf(installedApp, vpnServiceDeclaration),
            )
        val emptyCategory = CategoryResult(name = "Empty", detected = false, findings = emptyList())
        return DetectionCheckResult(
            geoIp = emptyCategory,
            directSigns = directSigns,
            indirectSigns = emptyCategory,
            locationSignals = emptyCategory,
            bypassResult =
                BypassResult(
                    proxyEndpoint = null,
                    directIp = null,
                    proxyIp = null,
                    xrayApiScanResult = null,
                    findings = emptyList(),
                    detected = false,
                ),
            verdict = Verdict.NEEDS_REVIEW,
            verdictExplanation =
                VerdictExplanation(
                    verdict = Verdict.NEEDS_REVIEW,
                    ruleApplied = "R6",
                    summary = "Local inventory evidence requires review",
                    appliedScopes = listOf(DetectionScope.LOCAL_INVENTORY),
                    uniqueSignalCount = 1,
                ),
        )
    }

    private fun targetedBypassEvidence(
        source: EvidenceSource,
        packageName: String,
        family: String,
    ) = EvidenceItem(
        source = source,
        detected = true,
        confidence = EvidenceConfidence.MEDIUM,
        description =
            when (source) {
                EvidenceSource.INSTALLED_APP -> "Targeted bypass app installed"
                else -> "Targeted bypass app declares VpnService"
            },
        family = family,
        packageName = packageName,
        kind = VpnAppKind.TARGETED_BYPASS,
    )

    private fun detectionStageRunnerWith(result: DetectionCheckResult): DefaultHomeDetectionStageRunner {
        val detectionRunner =
            object : DetectionCheckRunner {
                override suspend fun run(
                    context: Context,
                    config: DetectionRunnerConfig,
                    onProgress: (suspend (com.poyka.ripdpi.core.detection.DetectionProgress) -> Unit)?,
                ): DetectionCheckResult = result
            }
        return DefaultHomeDetectionStageRunner(
            context = RuntimeEnvironment.getApplication(),
            appSettingsRepository = FakeAppSettingsRepository(),
            detectionCheckRunner = detectionRunner,
        )
    }
}
