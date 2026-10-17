package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticsDetailLoader
import com.poyka.ripdpi.diagnostics.DiagnosticsResolverActions
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

internal class DiagnosticsMutationRunner(
    private val scope: CoroutineScope,
    val diagnosticsTimelineSource: DiagnosticsTimelineSource,
    val diagnosticsScanController: DiagnosticsScanController,
    val diagnosticsDetailLoader: DiagnosticsDetailLoader,
    val diagnosticsResolverActions: DiagnosticsResolverActions,
    val uiStateFactory: DiagnosticsUiStateFactory,
    private val effects: MutableSharedFlow<DiagnosticsEffect>,
    private val hapticFeedbackEnabled: suspend () -> Boolean = { true },
    val currentUiState: () -> DiagnosticsUiState,
) {
    fun launch(block: suspend DiagnosticsMutationRunner.() -> Unit) {
        scope.launch { block() }
    }

    suspend fun emit(effect: DiagnosticsEffect) {
        val sampledEffect =
            when (effect) {
                is DiagnosticsEffect.ScanCompleted -> {
                    effect.copy(hapticFeedbackEnabled = effect.hapticFeedbackEnabled && sampleHapticFeedbackEnabled())
                }

                is DiagnosticsEffect.ScanStartFailed -> {
                    effect.copy(hapticFeedbackEnabled = effect.hapticFeedbackEnabled && sampleHapticFeedbackEnabled())
                }

                else -> {
                    effect
                }
            }
        effects.emit(sampledEffect)
    }

    private suspend fun sampleHapticFeedbackEnabled(): Boolean {
        val enabled =
            try {
                hapticFeedbackEnabled()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        currentCoroutineContext().ensureActive()
        return enabled
    }
}
