package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsMetricUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsTone

internal fun buildStrategyProbeCandidateMetrics(
    summary: StrategyProbeCandidateSummary,
    recommended: Boolean,
    stringResolver: StringResolver,
): List<DiagnosticsMetricUiModel> =
    buildList {
        add(
            DiagnosticsMetricUiModel(
                stringResolver.getString(R.string.diagnostics_strategy_metric_targets),
                "${summary.succeededTargets}/${summary.totalTargets}",
            ),
        )
        add(
            DiagnosticsMetricUiModel(
                stringResolver.getString(R.string.diagnostics_strategy_metric_weight),
                "${summary.weightedSuccessScore}/${summary.totalWeight}",
            ),
        )
        add(
            DiagnosticsMetricUiModel(
                stringResolver.getString(R.string.status_stat_quality),
                summary.qualityScore.toString(),
                tone =
                    when {
                        summary.qualityScore >= summary.totalTargets.coerceAtLeast(1) * 3 -> DiagnosticsTone.Positive
                        summary.qualityScore > 0 -> DiagnosticsTone.Warning
                        else -> DiagnosticsTone.Neutral
                    },
            ),
        )
        summary.averageLatencyMs?.let {
            add(
                DiagnosticsMetricUiModel(
                    stringResolver.getString(R.string.profile_variants_metric_latency),
                    stringResolver.getString(R.string.unit_milliseconds_format, it),
                    DiagnosticsTone.Info,
                ),
            )
        }
        addAll(strategyEmitterMetrics(summary, stringResolver))
        if (recommended) {
            add(
                DiagnosticsMetricUiModel(
                    stringResolver.getString(R.string.adaptive_split_preset_selected),
                    if (summary.skipped) {
                        stringResolver.getString(
                            R.string.diagnostics_probe_status_fallback,
                        )
                    } else {
                        stringResolver.getString(R.string.diagnostics_strategy_selection_winner)
                    },
                    if (summary.skipped) DiagnosticsTone.Info else DiagnosticsTone.Positive,
                ),
            )
        }
    }
