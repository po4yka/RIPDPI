package com.poyka.ripdpi.services

import kotlinx.coroutines.Dispatchers
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
            withTimeout(timeoutMillis) {
                revokeSession(stopRuntime, destroyCoordinator, cleanupSocketProtection)
            }
        }
    }
}
