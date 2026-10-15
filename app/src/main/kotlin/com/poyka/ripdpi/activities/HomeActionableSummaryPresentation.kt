package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.DetectionScope
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeOutcome
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeCompositeStageStatus
import com.poyka.ripdpi.diagnostics.DiagnosticsHomeDetectionVerdict
import com.poyka.ripdpi.diagnostics.HomeBufferbloatGrade
import com.poyka.ripdpi.diagnostics.HomeDnsResolverClass
import com.poyka.ripdpi.platform.StringResolver

/** Match complete producer templates against their raw inputs; never replace text inside payloads. */
internal class HomeActionableSummaryPresentation(
    private val outcome: DiagnosticsHomeCompositeOutcome,
    private val strings: StringResolver,
) {
    fun headline(): String? {
        val parts = detectionHeadline() + countHeadlines()
        val generated = parts.takeIf { it.isNotEmpty() }?.joinToString(". ") { it.first }?.plus(".")
        return when {
            generated != null && outcome.actionableHeadline == generated -> {
                parts.joinToString(". ") { it.second } + "."
            }

            outcome.actionable && outcome.actionableHeadline == "Analysis complete and bypass applied." -> {
                strings.getString(R.string.home_diagnostics_actionable_applied)
            }

            else -> {
                outcome.actionableHeadline
            }
        }
    }

    fun nextSteps(): List<String> {
        val known = detectionSteps() + outcomeSteps()
        // Keep the source's ordering, distinct operation and four-item limit, including custom/legacy entries.
        return outcome.actionableNextSteps.map { raw -> known.firstOrNull { it.first == raw }?.second ?: raw }
    }

    private fun detectionHeadline(): List<Pair<String, String>> =
        when (outcome.detectionVerdict) {
            DiagnosticsHomeDetectionVerdict.DETECTED -> {
                if (DetectionScope.NETWORK_OBSERVATION in outcome.detectionEvidenceScopes) {
                    listOf(
                        "Network-observed VPN detection pattern requires review" to
                            strings.getString(R.string.home_diagnostics_actionable_network_detection),
                    )
                } else {
                    listOf(
                        "Local observer found VPN-related artifacts" to
                            strings.getString(R.string.home_diagnostics_actionable_local_detection),
                    )
                }
            }

            DiagnosticsHomeDetectionVerdict.NEEDS_REVIEW -> {
                listOf(
                    "Some detection checks need review (likely missing permissions)" to
                        strings.getString(R.string.home_diagnostics_actionable_detection_review),
                )
            }

            else -> {
                emptyList()
            }
        }

    private fun countHeadlines(): List<Pair<String, String>> =
        buildList {
            val count = outcome.installedVpnDetectorCount ?: 0
            if (count > 0) {
                add(
                    "$count VPN-detector app${if (count == 1) "" else "s"} installed" to
                        strings.getQuantityString(R.plurals.home_diagnostics_actionable_detector_count, count, count),
                )
            }
            if (outcome.actionable && outcome.appliedSettings.isNotEmpty()) {
                val size = outcome.appliedSettings.size
                val dns = outcome.appliedSettings.all { it.label in setOf("Resolver", "Protocol") }
                val kind = if (dns) "DNS" else "bypass"
                val resource =
                    if (dns) {
                        R.plurals.home_diagnostics_actionable_dns_count
                    } else {
                        R.plurals.home_diagnostics_actionable_bypass_count
                    }
                add(
                    "Applied $size $kind setting${if (size == 1) "" else "s"}" to
                        strings.getQuantityString(resource, size, size),
                )
            }
        }

    private fun detectionSteps(): List<Pair<String, String>> =
        buildList {
            if (outcome.detectionVerdict == DiagnosticsHomeDetectionVerdict.DETECTED) {
                outcome.detectionFindings.take(2).forEach { finding ->
                    add(
                        "Detection signal: $finding" to
                            strings.getString(R.string.home_diagnostics_actionable_detection_signal_format, finding),
                    )
                }
            }
            if ((outcome.installedVpnDetectorCount ?: 0) > 0) {
                val apps = outcome.installedVpnDetectorTopApps.take(2).joinToString(", ")
                if (apps.isNotBlank()) {
                    add(
                        "Add per-app routing for: $apps" to
                            strings.getString(R.string.home_diagnostics_actionable_routing_format, apps),
                    )
                }
            }
        }

    private fun outcomeSteps(): List<Pair<String, String>> =
        buildList {
            val stages =
                outcome.stageSummaries.filter { it.status == DiagnosticsHomeCompositeStageStatus.FAILED }.take(
                    2,
                )
            if (outcome.failedStageCount > 0 && stages.isNotEmpty()) {
                val labels = stages.joinToString(", ") { it.stageLabel }
                add("Failed: $labels" to strings.getString(R.string.home_diagnostics_actionable_failed_format, labels))
            }
            if (outcome.bufferbloat?.grade in setOf(HomeBufferbloatGrade.D, HomeBufferbloatGrade.F)) {
                add(
                    "High bufferbloat — consider QoS or a different relay transport" to
                        strings.getString(R.string.home_diagnostics_actionable_bufferbloat),
                )
            }
            if (outcome.dnsCharacterization?.resolverClass == HomeDnsResolverClass.DOH_UNREACHABLE) {
                add(
                    "Encrypted DNS endpoint unreachable — check connectivity and retry" to
                        strings.getString(R.string.home_diagnostics_actionable_dns_unreachable),
                )
            }
            outcome.regressionDelta?.newlyFailedStageKeys?.takeIf { it.isNotEmpty() }?.let { keys ->
                val labels = keys.joinToString(", ")
                add(
                    "Regression: $labels stopped working since the previous run" to
                        strings.getString(R.string.home_diagnostics_actionable_regression_format, labels),
                )
            }
            outcome.strategyEffectiveness
                .maxByOrNull { it.successCount - it.failureCount }
                ?.takeIf { it.successCount > 0 }
                ?.let { winner ->
                    add(
                        (
                            "Best historical edge on this network: ${winner.label} " +
                                "(${winner.successCount} pass / ${winner.failureCount} fail)"
                        ) to
                            strings.getString(
                                R.string.home_diagnostics_actionable_historical_format,
                                winner.label,
                                winner.successCount,
                                winner.failureCount,
                            ),
                    )
                }
        }
}
