package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.StrategyProbeAuditAssessment
import com.poyka.ripdpi.diagnostics.StrategyProbeAuditConfidenceLevel
import com.poyka.ripdpi.diagnostics.StrategyProbeReport

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

internal fun auditConfidenceLabel(level: StrategyProbeAuditConfidenceLevel): String =
    when (level) {
        StrategyProbeAuditConfidenceLevel.HIGH -> "High"
        StrategyProbeAuditConfidenceLevel.MEDIUM -> "Medium"
        StrategyProbeAuditConfidenceLevel.LOW -> "Low"
    }

internal fun auditConfidenceTone(level: StrategyProbeAuditConfidenceLevel): DiagnosticsTone =
    when (level) {
        StrategyProbeAuditConfidenceLevel.HIGH -> DiagnosticsTone.Positive
        StrategyProbeAuditConfidenceLevel.MEDIUM -> DiagnosticsTone.Warning
        StrategyProbeAuditConfidenceLevel.LOW -> DiagnosticsTone.Negative
    }

internal fun auditAssessmentMetrics(assessment: StrategyProbeAuditAssessment): List<DiagnosticsMetricUiModel> =
    listOf(
        DiagnosticsMetricUiModel(
            label = "Confidence",
            value = "${auditConfidenceLabel(assessment.confidence.level)} (${assessment.confidence.score}/100)",
            tone = auditConfidenceTone(assessment.confidence.level),
        ),
        DiagnosticsMetricUiModel(
            label = "Matrix coverage",
            value = "${assessment.coverage.matrixCoveragePercent}%",
            tone = coverageTone(assessment.coverage.matrixCoveragePercent, passingPercent = 75),
        ),
        DiagnosticsMetricUiModel(
            label = "Winner coverage",
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
