package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditAssessment
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditConfidenceLevel
import com.poyka.ripdpi.diagnostics.StrategyProbeReport
import com.poyka.ripdpi.platform.StringResolver

internal fun buildStrategyProbeSummaryMetrics(report: StrategyProbeReport): List<DiagnosticsMetricUiModel> {
    val candidates = report.tcpCandidates + report.quicCandidates
    val worked = candidates.count { it.outcome.equals("success", ignoreCase = true) }
    val partial = candidates.count { it.outcome.equals("partial", ignoreCase = true) }
    val failed =
        candidates.count { candidate ->
            !candidate.skipped &&
                !candidate.outcome.equals("success", ignoreCase = true) &&
                !candidate.outcome.equals("partial", ignoreCase = true) &&
                !candidate.outcome.equals("not_applicable", ignoreCase = true)
        }
    val notApplicable = candidates.count { it.outcome.equals("not_applicable", ignoreCase = true) }
    val skipped = candidates.count { it.skipped }
    return buildList {
        add(DiagnosticsMetricUiModel("Worked", worked.toString(), DiagnosticsTone.Positive))
        add(DiagnosticsMetricUiModel("Partial", partial.toString(), DiagnosticsTone.Warning))
        add(DiagnosticsMetricUiModel("Failed", failed.toString(), DiagnosticsTone.Negative))
        add(DiagnosticsMetricUiModel("N/A", notApplicable.toString(), DiagnosticsTone.Neutral))
        if (skipped > 0) {
            add(DiagnosticsMetricUiModel("Skipped", skipped.toString(), DiagnosticsTone.Neutral))
        }
    }
}

internal fun auditConfidenceLabel(
    level: StrategyProbeAuditConfidenceLevel,
    stringResolver: StringResolver,
): String =
    stringResolver.getString(
        when (level) {
            StrategyProbeAuditConfidenceLevel.HIGH -> R.string.diagnostics_audit_confidence_high
            StrategyProbeAuditConfidenceLevel.MEDIUM -> R.string.diagnostics_audit_confidence_medium
            StrategyProbeAuditConfidenceLevel.LOW -> R.string.diagnostics_audit_confidence_low
        },
    )

internal fun auditConfidenceTone(level: StrategyProbeAuditConfidenceLevel): DiagnosticsTone =
    when (level) {
        StrategyProbeAuditConfidenceLevel.HIGH -> DiagnosticsTone.Positive
        StrategyProbeAuditConfidenceLevel.MEDIUM -> DiagnosticsTone.Warning
        StrategyProbeAuditConfidenceLevel.LOW -> DiagnosticsTone.Negative
    }

internal fun auditAssessmentMetrics(
    assessment: StrategyProbeAuditAssessment,
    stringResolver: StringResolver,
): List<DiagnosticsMetricUiModel> =
    listOf(
        DiagnosticsMetricUiModel(
            label = stringResolver.getString(R.string.diagnostics_audit_confidence_label),
            value =
                stringResolver.getString(
                    R.string.diagnostics_audit_confidence_value,
                    auditConfidenceLabel(assessment.confidence.level, stringResolver),
                    assessment.confidence.score,
                ),
            tone = auditConfidenceTone(assessment.confidence.level),
        ),
        DiagnosticsMetricUiModel(
            label = stringResolver.getString(R.string.diagnostics_audit_matrix_coverage_label),
            value = "${assessment.coverage.matrixCoveragePercent}%",
            tone = coverageTone(assessment.coverage.matrixCoveragePercent, passingPercent = 75),
        ),
        DiagnosticsMetricUiModel(
            label = stringResolver.getString(R.string.diagnostics_audit_winner_coverage_label),
            value = "${assessment.coverage.winnerCoveragePercent}%",
            tone = coverageTone(assessment.coverage.winnerCoveragePercent, passingPercent = 50),
        ),
    )

private fun coverageTone(
    valuePercent: Int,
    passingPercent: Int,
): DiagnosticsTone =
    if (valuePercent >= passingPercent) {
        DiagnosticsTone.Positive
    } else {
        DiagnosticsTone.Warning
    }
