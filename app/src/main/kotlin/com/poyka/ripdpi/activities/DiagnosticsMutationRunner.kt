package com.poyka.ripdpi.activities

import co.touchlab.kermit.Logger
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
        scope.launch {
            // A row removed by retention between render and tap, or a storage failure, must not reach
            // the uncaught handler and kill the app and its VPN service. The log names only the class.
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ignored: Exception) {
                Logger.w { "Diagnostics action failed: ${ignored.javaClass.simpleName}" }
            }
        }
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
