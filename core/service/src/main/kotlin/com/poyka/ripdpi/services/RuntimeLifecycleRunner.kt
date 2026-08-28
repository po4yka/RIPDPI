package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RuntimeLifecycleRunner(
    private val mutex: Mutex,
    private val lifecycleState: ServiceLifecycleStateMachine,
    private val serviceLabel: () -> String,
    private val isStopping: () -> Boolean,
    private val setStopping: (Boolean) -> Unit,
) {
    @Suppress("detekt.TooGenericExceptionCaught")
    suspend fun start(
        shouldRecoverRunning: () -> Boolean = { false },
        recoverRunningBlock: suspend () -> Unit = {},
        startBlock: suspend () -> Unit,
    ): Throwable? =
        mutex.withLock {
            if (lifecycleState.state in
                setOf(
                    ServiceLifecycleStateMachine.State.RUNNING,
                    ServiceLifecycleStateMachine.State.STOPPING,
                ) && shouldRecoverRunning()
            ) {
                Logger.i { "Recovering failed ${serviceLabel()} runtime before start" }
                lifecycleState.beginStop()
                setStopping(true)
                try {
                    recoverRunningBlock()
                    lifecycleState.markStopped()
                } catch (failure: Exception) {
                    return@withLock failure
                } finally {
                    setStopping(false)
                }
            }

            if (!lifecycleState.tryBeginStart()) {
                Logger.d {
                    "Ignoring ${serviceLabel()} start while lifecycle state is ${lifecycleState.state}"
                }
                return@withLock null
            }

            try {
                startBlock()
                lifecycleState.markStarted()
                null
            } catch (failure: Exception) {
                lifecycleState.markStartFailed()
                failure
            }
        }

    suspend fun stop(
        guard: RuntimeStopGuard? = null,
        stopBlock: suspend () -> Unit,
    ): Boolean {
        if (isStopping()) {
            Logger.d { "${serviceLabel()} stop already in progress" }
            return false
        }

        return mutex.withLock {
            if (guard != null && !guard.isCurrent()) return@withLock false
            if (isStopping()) {
                Logger.d { "${serviceLabel()} stop already in progress" }
                return@withLock false
            }

            if (lifecycleState.state != ServiceLifecycleStateMachine.State.STOPPING) {
                lifecycleState.beginStop()
            }
            setStopping(true)
            var cleanupRetained = false
            try {
                stopBlock()
                true
            } catch (pending: RuntimeCleanupPendingException) {
                cleanupRetained = true
                throw pending
            } finally {
                if (!cleanupRetained) lifecycleState.markStopped()
                setStopping(false)
            }
        }
    }
}
