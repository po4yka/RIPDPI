package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionCheckRunner
import com.poyka.ripdpi.core.detection.Recommendation
import com.poyka.ripdpi.core.detection.StealthScore
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.services.RoutingProtectionCatalogService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns the cancellable probe-run lifecycle for the detection screen: reset -> probe (progress) ->
 * score/label -> recommendations -> report/debug-report -> suggested fixes -> save history ->
 * community refresh -> terminal result, plus error/cancellation handling and [stopCheck].
 *
 * Mutates the screen state exclusively through the shared [DetectionCheckStateReducer], on [scope]
 * (the host VM's `viewModelScope`). Side effects that belong to the auxiliary owner (history save,
 * community refresh) are delegated via [onSaveHistory] / [onRefreshCommunity] so they run inside the
 * run BEFORE the terminal emission, preserving the original ordering.
 */
internal class DetectionRunCoordinator(
    private val scope: CoroutineScope,
    private val reducer: DetectionCheckStateReducer,
    private val appSettingsRepository: AppSettingsRepository,
    private val detectionCheckRunner: DetectionCheckRunner,
    private val probeRunner: DetectionProbeRunner,
    private val routingProtectionCatalogService: RoutingProtectionCatalogService,
    private val stringResolver: StringResolver,
    private val onSaveHistory: suspend (DetectionCheckResult, Int) -> Unit,
    private val onRefreshCommunity: () -> Unit,
) {
    private var runJob: Job? = null

    fun startCheck() {
        if (reducer.value.isRunning) return
        runJob =
            scope.launch {
                reducer.mutate { it.resetForCheckRun() }
                val outcome = runCatching { runOnce() }

                outcome.onFailure { error ->
                    if (error is CancellationException) {
                        throw error
                    }
                    reducer.mutate {
                        it.copy(
                            isRunning = false,
                            progress = null,
                            error = error.message ?: stringResolver.getString(R.string.detection_error_unknown),
                        )
                    }
                }
            }
    }

    fun stopCheck() {
        runJob?.cancel()
        runJob = null
        reducer.mutate { it.copy(isRunning = false, progress = null) }
    }

    private suspend fun runOnce() {
        val settings = appSettingsRepository.settings.first()
        val config = DetectionResultPresenter.runnerConfig(settings, probeRunner.packageName)
        val result =
            probeRunner.runDetectionCheck(
                runner = detectionCheckRunner,
                config = config,
                onProgress = { progress ->
                    reducer.mutate { it.copy(progress = progress) }
                },
            )

        val score = StealthScore.compute(result)
        val label = StealthScore.label(score)
        val recommendations =
            DetectionResultPresenter.recommendations(
                result = result,
                settings = settings,
                snapshot = routingProtectionCatalogService.snapshot(),
                stringResolver = stringResolver,
            )
        val privacyModeEnabled = settings.detectionCheckPrivacyModeEnabled
        val reportText = DetectionResultPresenter.reportText(result, privacyModeEnabled)
        val debugReportText = DetectionResultPresenter.debugReportText(result, settings, privacyModeEnabled)
        val fixes = DetectionResultPresenter.suggestedFixes(settings, result)

        onSaveHistory(result, score)
        onRefreshCommunity()

        reducer.mutate {
            it.withCheckResult(
                result = result,
                score = score,
                label = label,
                recommendations = recommendations,
                fixes = fixes,
                reportText = reportText,
                debugReportText = debugReportText,
            )
        }
    }

    private fun DetectionCheckUiState.resetForCheckRun(): DetectionCheckUiState =
        copy(
            isRunning = true,
            progress = null,
            result = null,
            narrative = null,
            stealthScore = null,
            stealthLabel = null,
            recommendations = emptyList(),
            suggestedFixes = emptyList(),
            reportText = null,
            debugReportText = null,
            error = null,
        )

    private fun DetectionCheckUiState.withCheckResult(
        result: DetectionCheckResult,
        score: Int,
        label: String,
        recommendations: List<Recommendation>,
        fixes: List<DetectionSuggestedFix>,
        reportText: String,
        debugReportText: String?,
    ): DetectionCheckUiState =
        copy(
            isRunning = false,
            progress = null,
            result = result,
            narrative = result.verdictNarrative,
            stealthScore = score,
            stealthLabel = label,
            recommendations = recommendations,
            suggestedFixes = fixes,
            reportText = reportText,
            debugReportText = debugReportText,
        )
}
