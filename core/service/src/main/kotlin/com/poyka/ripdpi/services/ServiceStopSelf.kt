package com.poyka.ripdpi.services

internal fun requestStopSelfWithFallback(
    stopSelfStartId: Int?,
    stopSelfResult: (Int) -> Boolean,
    stopSelf: () -> Unit,
) {
    if (stopSelfStartId == null) {
        stopSelf()
    } else {
        // A false result means that a newer start command already exists. Falling back to
        // stopSelf() here would tear down that replacement session after a quick restart.
        stopSelfResult(stopSelfStartId)
    }
}
