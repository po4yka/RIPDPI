package com.poyka.ripdpi.activities

import com.poyka.ripdpi.diagnostics.DiagnosticsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch

internal class DiagnosticsMutationRunner(
    private val scope: CoroutineScope,
    val diagnosticsManager: DiagnosticsManager,
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
