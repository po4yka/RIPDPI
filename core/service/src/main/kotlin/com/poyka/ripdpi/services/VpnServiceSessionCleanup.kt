package com.poyka.ripdpi.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

internal class VpnServiceSessionCleanup {
    private var nativeProtectUnregistered = false
    private var protectSocketServerStopped = false
    private val coordinatorDestroyed = AtomicBoolean(false)

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

    fun destroyCoordinator(destroy: () -> Unit) {
        if (!coordinatorDestroyed.compareAndSet(false, true)) return
        destroy()
    }

    fun destroySession(
        destroyCoordinator: () -> Unit,
        cleanupSocketProtection: () -> Unit,
    ) {
        try {
            destroyCoordinator(destroyCoordinator)
        } finally {
            cleanupSocketProtection()
        }
    }

    suspend fun revokeSession(
        stopRuntime: suspend () -> Unit,
        destroyCoordinator: () -> Unit,
        cleanupSocketProtection: () -> Unit,
    ) {
        stopRuntime()
        destroySession(destroyCoordinator, cleanupSocketProtection)
    }

    fun destroyRunningSession(
        stopRuntime: suspend () -> Unit,
        destroyCoordinator: () -> Unit,
        cleanupSocketProtection: () -> Unit,
        timeoutMillis: Long,
    ) {
        runBlocking(Dispatchers.IO) {
            // A stop that outlives the budget must not orphan the native
            // session: the coordinator destroy and the protect registration
            // cleanup are both idempotent, so they run unconditionally and the
            // timeout is rethrown afterwards for the caller to record. Only
            // the timeout is contained here; a stop that fails on its own
            // keeps the revokeSession contract (see
            // runtimeStopFailureKeepsSocketProtectionRegistered).
            val stopTimeout: TimeoutCancellationException? =
                try {
                    withTimeout(timeoutMillis) {
                        stopRuntime()
                    }
                    null
                } catch (timeout: TimeoutCancellationException) {
                    timeout
                }
            destroySession(destroyCoordinator, cleanupSocketProtection)
            stopTimeout?.let { throw it }
        }
    }
}
