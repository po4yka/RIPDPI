package com.poyka.ripdpi.services

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

internal class ServiceRuntimeLoopOwner(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    permissionWatchdog: PermissionWatchdog,
    onPermissionRevoked: suspend (PermissionChangeEvent) -> Unit,
) {
    private val telemetryLoopCoordinator =
        ServiceTelemetryLoopCoordinator(
            scope = scope,
            dispatcher = dispatcher,
        )
    private val permissionWatchdogCoordinator =
        PermissionWatchdogCoordinator(
            scope = scope,
            dispatcher = dispatcher,
            permissionWatchdog = permissionWatchdog,
            onPermissionRevoked = onPermissionRevoked,
        )

    fun startPermissionWatchdog() {
        permissionWatchdogCoordinator.start()
    }

    fun replaceTelemetryJob(block: suspend CoroutineScope.() -> Unit) {
        telemetryLoopCoordinator.replace(block)
    }

    fun cancelPermissionWatchdog() {
        permissionWatchdogCoordinator.cancel()
    }

    fun cancelTelemetry() {
        telemetryLoopCoordinator.cancel()
    }
}
