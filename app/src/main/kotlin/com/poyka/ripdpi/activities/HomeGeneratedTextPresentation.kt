package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.platform.StringResolver

private val homeConfidencePattern = Regex("Confidence (low|medium|high) \\(([0-9]+)\\)")

internal fun String?.homeConfidenceLevel(): HomeConfidenceLevel? =
    this?.let(homeConfidencePattern::matchEntire)?.groupValues?.get(1)?.let {
        when (it) {
            "low" -> HomeConfidenceLevel.LOW
            "medium" -> HomeConfidenceLevel.MEDIUM
            "high" -> HomeConfidenceLevel.HIGH
            else -> null
        }
    }

internal fun StringResolver.homeConfidence(value: String?): String? =
    value?.let { raw ->
        homeConfidencePattern.matchEntire(raw)?.let { match ->
            getString(
                R.string.home_generated_confidence,
                homeGeneratedValue(match.groupValues[1]),
                homeCount(match.groupValues[2]),
            )
        } ?: raw
    }

internal fun StringResolver.homeStageLabel(
    key: String,
    fallback: String,
): String = homeGeneratedStageResources[key]?.let(::getString) ?: fallback

/** Translate only full, known producer templates. Stored and exported evidence stays unchanged. */
internal fun StringResolver.homeGeneratedText(raw: String): String {
    homeGeneratedSentenceResources[raw]?.let { return getString(it) }
    val counts = homeConnectivitySummary(raw)
    if (counts != null) return counts
    return homeCompositeSummary(raw)
        ?: homeGeneratedTemplate(raw)
        ?: homeGeneratedRecommendation(raw)
        ?: homeDetectionProgress(raw)
        ?: raw
}

private fun StringResolver.homeCompositeSummary(raw: String): String? {
    val warning = homeWarningSentences.firstOrNull { raw.endsWith(" $it") }
    val coverageGap = homeCoverageGapSuffix.matchEntire(raw)
    val partialPrefix = "Scan completed with partial results · "
    val completed = homeCompletedStages.matchEntire(raw)
    return when {
        warning != null -> {
            homeGeneratedText(raw.removeSuffix(" $warning")) + " " + homeGeneratedText(warning)
        }

        coverageGap != null -> {
            homeGeneratedText(coverageGap.groupValues[1]) + " " + homeGeneratedText(coverageGap.groupValues[2])
        }

        raw.startsWith(partialPrefix) -> {
            homeGeneratedText(partialPrefix.removeSuffix(" · ")) + " · " +
                homeGeneratedText(raw.removePrefix(partialPrefix))
        }

        completed != null -> {
            val (count, total, failures) = completed.destructured
            val base = getString(R.string.home_generated_stages_completed, homeCount(count), homeCount(total))
            if (failures.isEmpty()) base else "$base ${homeGeneratedText(failures)}"
        }

        else -> {
            null
        }
    }
}

private val homeWarningSentences =
    listOf(
        "DNS issues were detected during the audit.",
        "Network changed during analysis — results may not reflect current network.",
    )
private val homeCoverageGapSuffix =
    Regex("(.+) ([0-9]+ additional domains? showed connectivity issues not covered by the strategy probe\\.)")
private val homeCompletedStages =
    Regex(
        "Completed ([0-9]+) of ([0-9]+) diagnostics stages\\.(?: ([0-9]+ stages? finished with " +
            "failures or were unavailable\\.))?",
    )

private data class HomeGeneratedTemplate(
    val pattern: Regex,
    val resource: Int,
    val quantityGroup: Int? = null,
)

private fun StringResolver.homeGeneratedTemplate(raw: String): String? {
    for (template in homeGeneratedTemplates) {
        val match = template.pattern.matchEntire(raw) ?: continue
        val arguments = match.groupValues.drop(1)
        val args =
            if (template.resource in homeGeneratedRawParameterTemplates) {
                arguments.toTypedArray()
            } else {
                arguments.map(::homeCount).toTypedArray()
            }
        return if (template.quantityGroup == null) {
            getString(template.resource, *args)
        } else {
            match.groupValues[template.quantityGroup].toIntOrNull()?.let { quantity ->
                getQuantityString(template.resource, quantity, *args)
            }
        }
    }
    return null
}

private val homeGeneratedRawParameterTemplates =
    setOf(R.string.home_generated_vpn_captured, R.string.home_generated_settlement)

private val homeGeneratedTemplates =
    listOf(
        HomeGeneratedTemplate(
            Regex("Analysis finished — ([0-9]+) of ([0-9]+) stages failed"),
            R.string.home_generated_analysis_failed,
        ),
        HomeGeneratedTemplate(
            Regex("([0-9]+) stages? finished with failures or were unavailable\\."),
            R.plurals.home_generated_stages_failed,
            1,
        ),
        HomeGeneratedTemplate(
            Regex("([0-9]+) additional domains? showed connectivity issues not covered by the strategy probe\\."),
            R.plurals.home_generated_coverage_gap,
            1,
        ),
        HomeGeneratedTemplate(Regex("Matrix ([0-9]+)% · winners ([0-9]+)%"), R.string.home_generated_coverage),
        HomeGeneratedTemplate(
            Regex("Comparing ([0-9]+) domain controls/failures and ([0-9]+) service targets in path\\."),
            R.string.home_generated_path_targets,
        ),
        HomeGeneratedTemplate(
            Regex(
                "Captured sessionless VPN route evidence: route=(\\S+) forwarding=(\\S+) " +
                    "target_reachability=unverified\\.",
            ),
            R.string.home_generated_vpn_captured,
        ),
        HomeGeneratedTemplate(
            Regex("Raw-path runtime settlement inconclusive: (.+)"),
            R.string.home_generated_settlement,
        ),
    )

internal fun StringResolver.homeStageSummary(
    key: String,
    label: String,
    raw: String,
): String {
    val expectedLabel = label.lowercase(java.util.Locale.ROOT)
    val template = homeStageSummaryTemplates.firstOrNull { raw == it.first.format(expectedLabel) }
    val controls = homePathControls.matchEntire(raw)
    return when {
        template != null -> {
            getString(template.second, homeStageLabel(key, label))
        }

        key == "path_comparison" && controls != null -> {
            getString(
                R.string.home_generated_path_controls,
                homeGeneratedText(controls.groupValues[1]),
                controls.groupValues[2],
                controls.groupValues[3],
            )
        }

        else -> {
            homeGeneratedText(raw)
        }
    }
}

private val homeStageSummaryTemplates =
    listOf(
        "Starting %s." to R.string.home_generated_stage_start,
        "Collecting diagnostics for %s." to R.string.home_generated_stage_collect,
        "Unable to start %s." to R.string.home_generated_stage_unable,
    )
private val homePathControls = Regex("(.+) Controls=(\\[.*]) failed=(\\[.*])")

private fun StringResolver.homeConnectivitySummary(raw: String): String? {
    val counts = homeConnectivityCounts.matchEntire(raw)?.groupValues?.drop(1) ?: return null
    return counts
        .zip(homeConnectivityLabels)
        .filter { (count, _) -> count.isNotEmpty() }
        .joinToString(" · ") { (count, label) -> getString(label, homeCount(count)) }
}

private val homeConnectivityCounts =
    Regex(
        "([0-9]+) completed · ([0-9]+) healthy(?: · ([0-9]+) attention)?(?: · ([0-9]+) failed)?(?: · " +
            "([0-9]+) inconclusive)?",
    )
private val homeConnectivityLabels =
    listOf(
        R.string.diagnostics_scan_count_completed_format,
        R.string.diagnostics_scan_count_healthy_format,
        R.string.diagnostics_scan_count_attention_format,
        R.string.diagnostics_scan_count_failed_format,
        R.string.diagnostics_scan_count_inconclusive_format,
    )
