package com.poyka.ripdpi.ui.screens.diagnostics

import com.poyka.ripdpi.R
import com.poyka.ripdpi.diagnostics.StrategyEmitterTier
import com.poyka.ripdpi.diagnostics.StrategyProbeCandidateSummary
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsMetricUiModel
import com.poyka.ripdpi.ui.screens.diagnostics.state.DiagnosticsTone

internal fun strategyEmitterMetrics(
    summary: StrategyProbeCandidateSummary,
    stringResolver: StringResolver,
): List<DiagnosticsMetricUiModel> =
    listOf(strategyEmitterMetric(summary, stringResolver), strategyRealizationMetric(summary, stringResolver))

internal fun StrategyProbeCandidateSummary.presentedRationale(): String = rationale

internal fun StrategyProbeCandidateSummary.presentedNotes(): List<String> = notes

internal fun StrategyProbeCandidateSummary.canReplayInDistribution(): Boolean = true

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
            stringResolver.getString(R.string.status_connection_actuator_direct)
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
