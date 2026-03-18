package com.poyka.ripdpi.activities

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch

internal class MainMutationRunner(
    private val scope: CoroutineScope,
    private val effects: SendChannel<MainEffect>,
    val currentUiState: () -> MainUiState,
) {
    fun launch(block: suspend MainMutationRunner.() -> Unit): Job =
        scope.launch { block() }

    fun trySend(effect: MainEffect) {
        effects.trySend(effect)
    }

    suspend fun emit(effect: MainEffect) {
        effects.send(effect)
    }
}
