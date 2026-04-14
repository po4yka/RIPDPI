package com.poyka.ripdpi.activities

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

internal class MainMutationRunner(
    private val scope: CoroutineScope,
    private val effects: MutableSharedFlow<MainEffect>,
    val currentUiState: () -> MainUiState,
) {
    fun launch(block: suspend MainMutationRunner.() -> Unit): Job = scope.launch { block() }

    fun trySend(effect: MainEffect) {
        effects.tryEmit(effect)
    }

    suspend fun emit(effect: MainEffect) {
        effects.emit(effect)
    }
}
