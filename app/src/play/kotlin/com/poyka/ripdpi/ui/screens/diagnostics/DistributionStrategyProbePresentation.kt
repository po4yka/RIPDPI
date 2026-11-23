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
    buildList {
        val lab = summary.emitterTier == StrategyEmitterTier.LAB_DIAGNOSTICS_ONLY
        if (summary.canReplayInDistribution() || summary.emitterDowngraded) {
            val emitter =
                if (lab) R.string.diagnostics_strategy_emitter_lab else R.string.diagnostics_strategy_emitter_standard
            add(
                DiagnosticsMetricUiModel(
                    stringResolver.getString(R.string.diagnostics_strategy_metric_emitter),
                    stringResolver.getString(emitter),
                    if (lab) DiagnosticsTone.Neutral else DiagnosticsTone.Info,
                ),
            )
            val realization =
                when {
                    summary.emitterDowngraded -> R.string.diagnostics_strategy_realization_approximate
                    lab -> R.string.diagnostics_strategy_realization_lab
                    else -> R.string.status_connection_actuator_direct
                }
            add(
                DiagnosticsMetricUiModel(
                    stringResolver.getString(R.string.diagnostics_strategy_metric_realization),
                    stringResolver.getString(realization),
                    if (summary.emitterDowngraded) DiagnosticsTone.Warning else DiagnosticsTone.Neutral,
                ),
            )
        }
    }

internal fun strategyEmitterNotes(
    summary: StrategyProbeCandidateSummary,
    stringResolver: StringResolver,
): List<String> =
    buildList {
        if (summary.emitterDowngraded) {
            add(stringResolver.getString(R.string.diagnostics_strategy_note_approximate))
        }
        if (summary.emitterTier == StrategyEmitterTier.LAB_DIAGNOSTICS_ONLY) {
            add(stringResolver.getString(R.string.diagnostics_strategy_note_lab))
        }
    }

internal fun StrategyProbeCandidateSummary.presentedRationale(): String =
    rationale
        .takeUnless {
            exactEmitterRequiresRoot
        }.orEmpty()

internal fun StrategyProbeCandidateSummary.presentedNotes(): List<String> =
    notes
        .takeUnless {
            exactEmitterRequiresRoot
        }.orEmpty()

internal fun StrategyProbeCandidateSummary.canReplayInDistribution(): Boolean =
    !exactEmitterRequiresRoot &&
        (
            emitterTier == StrategyEmitterTier.NON_ROOT_PRODUCTION ||
                emitterTier == StrategyEmitterTier.LAB_DIAGNOSTICS_ONLY
        )
