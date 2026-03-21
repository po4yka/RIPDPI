package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticsDetailLoader
import com.poyka.ripdpi.diagnostics.DiagnosticsResolverActions
import com.poyka.ripdpi.diagnostics.DiagnosticsScanController
import com.poyka.ripdpi.diagnostics.DiagnosticsShareService
import com.poyka.ripdpi.diagnostics.DiagnosticsTimelineSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch

internal class DiagnosticsMutationRunner(
    private val scope: CoroutineScope,
    val diagnosticsTimelineSource: DiagnosticsTimelineSource,
    val diagnosticsScanController: DiagnosticsScanController,
    val diagnosticsDetailLoader: DiagnosticsDetailLoader,
    val diagnosticsShareService: DiagnosticsShareService,
    val diagnosticsResolverActions: DiagnosticsResolverActions,
    val uiStateFactory: DiagnosticsUiStateFactory,
    private val effects: SendChannel<DiagnosticsEffect>,
    val currentUiState: () -> DiagnosticsUiState,
) {
    fun launch(block: suspend DiagnosticsMutationRunner.() -> Unit) {
        scope.launch { block() }
    }

    suspend fun emit(effect: DiagnosticsEffect) {
        effects.send(effect)
    }
}
