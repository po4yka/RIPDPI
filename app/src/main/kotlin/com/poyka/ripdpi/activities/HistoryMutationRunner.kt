package com.poyka.ripdpi.activities

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class HistoryMutationRunner(
    private val scope: CoroutineScope,
) {
    fun launch(block: suspend HistoryMutationRunner.() -> Unit) {
        scope.launch { block() }
    }
}
