package com.poyka.ripdpi.services

internal fun requestStopSelfWithFallback(
    stopSelfStartId: Int?,
    stopSelfResult: (Int) -> Boolean,
    stopSelf: () -> Unit,
) {
    val stoppedSelf = stopSelfStartId?.let(stopSelfResult)
    if (stoppedSelf != true) {
        stopSelf()
    }
}
