package com.poyka.ripdpi.ui.screens.detection

import com.poyka.ripdpi.R
import com.poyka.ripdpi.core.detection.DetectionCheckResult
import com.poyka.ripdpi.core.detection.DetectionCheckRunner
import com.poyka.ripdpi.core.detection.Recommendation
import com.poyka.ripdpi.core.detection.StealthScore
import com.poyka.ripdpi.data.AppSettingsRepository
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.services.RoutingProtectionCatalogService
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns the cancellable probe-run lifecycle for the detection screen: reset -> probe (progress) ->
 * score/label -> recommendations -> report/debug-report -> save history ->
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
    private val onTerminalResult: suspend (DetectionCheckEffect) -> Unit = {},
) {
    private var runJob: Job? = null

    fun startCheck() {
        if (reducer.value.isRunning || runJob?.isActive == true) return
        runJob =
            scope.launch {
                reducer.mutate { it.resetForCheckRun() }
                val outcome = runCatching { runOnce() }

                outcome.onSuccess { result ->
                    deliverFeedback(DetectionCheckEffect.CheckCompleted(result.verdict))
                }
                outcome.onFailure { error ->
                    if (error is CancellationException) {
                        throw error
                    }
                    currentCoroutineContext().ensureActive()
                    reducer.mutate {
                        it.copy(
                            isRunning = false,
                            progress = null,
                            error = error.message ?: stringResolver.getString(R.string.detection_error_unknown),
                        )
                    }
                    deliverFeedback(DetectionCheckEffect.CheckFailed)
                }
            }
    }

    fun stopCheck() {
        runJob?.cancel()
        runJob = null
        reducer.mutate { it.copy(isRunning = false, progress = null) }
    }

    private suspend fun runOnce(): DetectionCheckResult {
        val settings = appSettingsRepository.settings.first()
        val config = DetectionResultPresenter.runnerConfig(settings, probeRunner.packageName)
        val runContext = currentCoroutineContext()
        val result =
            probeRunner.runDetectionCheck(
                runner = detectionCheckRunner,
                config = config,
                onProgress = { progress ->
                    runContext.ensureActive()
                    reducer.mutate { it.copy(progress = progress) }
                },
            )

        currentCoroutineContext().ensureActive()
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

        onSaveHistory(result, score)
        onRefreshCommunity()

        currentCoroutineContext().ensureActive()
        reducer.mutate {
            it.withCheckResult(
                result = result,
                score = score,
                label = label,
                recommendations = recommendations,
                reportText = reportText,
                debugReportText = debugReportText,
            )
        }
        return result
    }

    private suspend fun deliverFeedback(effect: DetectionCheckEffect) {
        // An optional feedback read must not turn an existing result into a failure.
        val enabled =
            runCatching { !appSettingsRepository.snapshot().hapticFeedbackDisabled }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    false
                }
        currentCoroutineContext().ensureActive()
        if (enabled) onTerminalResult(effect)
    }

    private fun DetectionCheckUiState.resetForCheckRun(): DetectionCheckUiState =
        copy(
            isRunning = true,
            progress = null,
            result = null,
            narrative = null,
            stealthScore = null,
            stealthLabel = null,
            recommendations = persistentListOf(),
            reportText = null,
            debugReportText = null,
            error = null,
        )

    private fun DetectionCheckUiState.withCheckResult(
        result: DetectionCheckResult,
        score: Int,
        label: String,
        recommendations: List<Recommendation>,
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
            recommendations = recommendations.toImmutableList(),
            reportText = reportText,
            debugReportText = debugReportText,
        )
}
