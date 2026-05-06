package com.poyka.ripdpi.services

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class ServiceTelemetryLoopCoordinator(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private var job: Job? = null

    fun replace(block: suspend CoroutineScope.() -> Unit) {
        cancel()
        job = scope.launch(dispatcher, block = block)
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
