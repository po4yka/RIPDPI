package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class PermissionWatchdogCoordinator(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val permissionWatchdog: PermissionWatchdog,
    private val onPermissionRevoked: suspend (runtimeId: String, PermissionChangeEvent) -> Unit,
) {
    private var job: Job? = null

    /** Watches permissions for the session with [runtimeId]; every event is reported against it. */
    fun start(runtimeId: String) {
        cancel()
        job =
            scope.launch(dispatcher) {
                permissionWatchdog.changes.collect { event ->
                    Logger.i { "Permission change detected: ${event.kind}" }
                    onPermissionRevoked(runtimeId, event)
                }
            }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
