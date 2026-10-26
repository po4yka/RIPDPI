package com.poyka.ripdpi.ui.diagnostics

import com.poyka.ripdpi.R
import com.poyka.ripdpi.activities.DiagnosticsMetricUiModel
import com.poyka.ripdpi.activities.DiagnosticsTone
import com.poyka.ripdpi.diagnostics.StrategyEmitterTier
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.platform.StringResolver

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
                stringResolver.getString(R.string.home_stat_quality),
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
        add(strategyEmitterMetric(summary, stringResolver))
        add(strategyRealizationMetric(summary, stringResolver))
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

private fun strategyEmitterMetric(
    summary: StrategyProbeCandidateSummary,
    stringResolver: StringResolver,
): DiagnosticsMetricUiModel =
    DiagnosticsMetricUiModel(
        stringResolver.getString(R.string.diagnostics_strategy_metric_emitter),
        strategyEmitterTierLabel(summary.emitterTier, stringResolver),
        tone =
            when (summary.emitterTier) {
                StrategyEmitterTier.NON_ROOT_PRODUCTION -> DiagnosticsTone.Info
                StrategyEmitterTier.ROOTED_PRODUCTION -> DiagnosticsTone.Warning
                StrategyEmitterTier.LAB_DIAGNOSTICS_ONLY -> DiagnosticsTone.Neutral
            },
    )

private fun strategyRealizationMetric(
    summary: StrategyProbeCandidateSummary,
    stringResolver: StringResolver,
): DiagnosticsMetricUiModel =
    DiagnosticsMetricUiModel(
        stringResolver.getString(R.string.diagnostics_strategy_metric_realization),
        strategyEmitterRealizationLabel(summary, stringResolver),
        tone =
            when {
                summary.emitterDowngraded -> DiagnosticsTone.Warning
                summary.exactEmitterRequiresRoot -> DiagnosticsTone.Info
                else -> DiagnosticsTone.Neutral
            },
    )

private fun strategyEmitterTierLabel(
    tier: StrategyEmitterTier,
    stringResolver: StringResolver,
): String =
    when (tier) {
        StrategyEmitterTier.NON_ROOT_PRODUCTION -> {
            stringResolver.getString(
                R.string.diagnostics_strategy_emitter_non_root,
            )
        }

        StrategyEmitterTier.ROOTED_PRODUCTION -> {
            stringResolver.getString(R.string.diagnostics_strategy_emitter_rooted)
        }

        StrategyEmitterTier.LAB_DIAGNOSTICS_ONLY -> {
            stringResolver.getString(R.string.diagnostics_strategy_emitter_lab)
        }
    }

private fun strategyEmitterRealizationLabel(
    summary: StrategyProbeCandidateSummary,
    stringResolver: StringResolver,
): String =
    when {
        summary.emitterDowngraded -> {
            stringResolver.getString(R.string.diagnostics_strategy_realization_approximate)
        }

        summary.exactEmitterRequiresRoot -> {
            stringResolver.getString(R.string.diagnostics_strategy_realization_rooted)
        }

        summary.emitterTier == StrategyEmitterTier.LAB_DIAGNOSTICS_ONLY -> {
            stringResolver.getString(
                R.string.diagnostics_strategy_realization_lab,
            )
        }

        else -> {
            stringResolver.getString(R.string.home_connection_actuator_direct)
        }
    }

internal fun strategyEmitterNotes(
    summary: StrategyProbeCandidateSummary,
    stringResolver: StringResolver,
): List<String> =
    buildList {
        if (summary.exactEmitterRequiresRoot && !summary.emitterDowngraded) {
            add(stringResolver.getString(R.string.diagnostics_strategy_note_rooted))
        }
        if (summary.emitterDowngraded) {
            add(stringResolver.getString(R.string.diagnostics_strategy_note_approximate))
        }
        if (summary.emitterTier == StrategyEmitterTier.LAB_DIAGNOSTICS_ONLY) {
            add(stringResolver.getString(R.string.diagnostics_strategy_note_lab))
        }
    }
