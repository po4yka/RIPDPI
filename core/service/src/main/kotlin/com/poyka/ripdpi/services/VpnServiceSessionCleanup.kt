package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

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

// Keep protection and the old owner until native teardown is confirmed, including after failure.
internal suspend fun awaitVpnRuntimeRelease(
    timeoutMillis: Long,
    release: suspend () -> RuntimeStopOutcome,
) {
    var reportedPending = false
    while (true) {
        var pendingFailure: Exception? = null
        val outcome =
            try {
                withTimeoutOrNull(timeoutMillis) { release() }
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Exception,
            ) {
                pendingFailure = failure
                RuntimeStopOutcome.CleanupPending
            }
        if (outcome == RuntimeStopOutcome.FullyReleased) return
        if (!reportedPending) {
            Logger.e(pendingFailure) { "VPN destruction retained cleanup ownership" }
            reportedPending = true
        }
        delay(VpnDestructionRetryDelayMillis)
    }
}

private const val VpnDestructionRetryDelayMillis = 250L
