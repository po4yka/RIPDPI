package com.poyka.ripdpi.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal class VpnServiceSessionCleanup {
    private var nativeProtectUnregistered = false
    private var protectSocketServerStopped = false
    private var coordinatorDestroyed = false

    @Synchronized
    fun cleanupNativeProtect(
        unregisterNativeProtect: () -> Unit,
        stopProtectSocketServer: () -> Unit,
    ) {
        var failure: Throwable? = null
        if (!nativeProtectUnregistered) {
            runCatching(unregisterNativeProtect)
                .onSuccess { nativeProtectUnregistered = true }
                .onFailure { failure = it }
        }
        if (!protectSocketServerStopped) {
            runCatching(stopProtectSocketServer)
                .onSuccess { protectSocketServerStopped = true }
                .onFailure { current ->
                    failure?.addSuppressed(current) ?: run { failure = current }
                }
        }
        failure?.let { throw it }
    }

    @Synchronized
    fun destroyCoordinator(destroy: () -> Unit) {
        if (coordinatorDestroyed) return
        destroy()
        coordinatorDestroyed = true
    }

    @Synchronized
    fun destroySession(
        destroyCoordinator: () -> Unit,
        cleanupSocketProtection: () -> Unit,
    ) {
        destroyCoordinator(destroyCoordinator)
        cleanupSocketProtection()
    }

    suspend fun revokeSession(
        stopRuntime: suspend () -> RuntimeStopOutcome,
        destroyCoordinator: () -> Unit,
        cleanupSocketProtection: () -> Unit,
    ) {
        // A timeout or failed stop leaves native completion unknown. Keep its protection for retry.
        if (stopRuntime() != RuntimeStopOutcome.FullyReleased) throw RuntimeCleanupPendingException()
        destroySession(destroyCoordinator, cleanupSocketProtection)
    }

    fun destroyRunningSession(
        stopRuntime: suspend () -> RuntimeStopOutcome,
        destroyCoordinator: () -> Unit,
        cleanupSocketProtection: () -> Unit,
        timeoutMillis: Long,
    ) {
        runBlocking(Dispatchers.IO) {
            withTimeout(timeoutMillis) {
                revokeSession(stopRuntime, destroyCoordinator, cleanupSocketProtection)
            }
        }
    }
}
