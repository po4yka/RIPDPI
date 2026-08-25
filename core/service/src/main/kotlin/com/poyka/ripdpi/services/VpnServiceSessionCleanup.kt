package com.poyka.ripdpi.services

import kotlinx.coroutines.CancellationException
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

    // Any stop failure must be captured verbatim so the guaranteed teardown
    // below still runs before the original error is rethrown; narrowing the
    // type would let an unexpected stop error orphan the native session.
    @Suppress("TooGenericExceptionCaught")
    suspend fun revokeSession(
        stopRuntime: suspend () -> Unit,
        destroyCoordinator: () -> Unit,
        cleanupSocketProtection: () -> Unit,
    ) {
        // A stop that fails on its own must not orphan the native session:
        // the coordinator destroy and the protect registration cleanup are
        // both idempotent, so they run before the stop failure is rethrown
        // for the caller to record. Outer cancellation still propagates
        // immediately; the destroyRunningSession path owns guaranteed
        // teardown for that case.
        var stopError: Throwable? = null
        try {
            stopRuntime()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (stopFailure: Throwable) {
            stopError = stopFailure
        }
        destroySession(destroyCoordinator, cleanupSocketProtection)
        stopError?.let { throw it }
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
            // timeout is rethrown afterwards for the caller to record.
            // revokeSession applies the same guarantee for stops that fail on
            // their own; only outer cancellation skips teardown here.
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
