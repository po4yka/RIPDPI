package com.poyka.ripdpi.services

import android.net.VpnService
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.startAction
import com.poyka.ripdpi.data.stopAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal const val notificationStopAction = "notification_stop"
internal const val diagnosticsStopAction = "diagnostics_stop"
internal const val diagnosticsStartAction = "diagnostics_start"
internal const val diagnosticsCompensatingStopAction = "diagnostics_compensating_stop"
internal const val transportFailoverRestartAction = "transport_failover_restart"
internal const val transportFailoverRequestIdExtra = "transport_failover_request_id"
internal const val transportFailoverTargetKindExtra = "transport_failover_target_kind"
internal const val transportFailoverTargetProfileIdExtra = "transport_failover_target_profile_id"
internal const val startupFallbackStartAction = "startup_fallback_start"
internal const val bootRecoveryStartAction = "boot_recovery_start"
internal const val packageReplacedRecoveryStartAction = "package_replaced_recovery_start"
internal const val processDeathRecoveryStartAction = "process_death_recovery_start"

internal fun isServiceRecoveryStartAction(action: String?): Boolean =
    action == null ||
        action == VpnService.SERVICE_INTERFACE ||
        action == bootRecoveryStartAction ||
        action == packageReplacedRecoveryStartAction ||
        action == processDeathRecoveryStartAction

internal class TransportFailoverCommandHandler(
    val restart: suspend (Long, TransportFailoverTarget) -> Unit,
    val reject: (Long) -> Unit = {},
)

internal class ServiceShellDelegate(
    private val serviceScope: CoroutineScope,
    private val serviceLabel: String,
    private val onStart: suspend () -> Unit,
    private val onStartWithId: suspend (String?, Int) -> Unit = { _, _ -> onStart() },
    private val onStop: suspend (Int?) -> Unit,
    private val transportFailoverCommandHandler: TransportFailoverCommandHandler =
        TransportFailoverCommandHandler(restart = { _, _ -> onStart() }),
    private val beforeUserStart: suspend () -> Unit = {},
    private val shouldPrepareUserStart: () -> Boolean = { true },
    private val isStopAllowed: (String) -> Boolean = { true },
    private val onAcceptedStart: () -> Unit = {},
    private val onAcceptedStop: () -> Unit = {},
    private val isCompensatingStopCurrent: () -> Boolean = { true },
    private val onRevoke: (suspend () -> Unit)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private class QueuedCommand(
        val block: suspend () -> Unit,
        val onDrop: () -> Unit,
        val cancellableByUserStop: Boolean,
        val acceptedStopEpoch: Long,
    ) {
        private val dispositionClaimed = AtomicBoolean(false)

        fun markExecuted() {
            dispositionClaimed.compareAndSet(false, true)
        }

        fun cancelWithoutExecution() {
            if (dispositionClaimed.compareAndSet(false, true)) {
                onDrop()
            }
        }
    }

    private data class ActiveCommand(
        val command: QueuedCommand,
        val job: Job,
    )

    private val commandStateLock = Any()
    private var acceptedStopEpoch = 0L
    private var activeCommand: ActiveCommand? = null

    private val commandQueue =
        Channel<QueuedCommand>(
            capacity = Channel.UNLIMITED,
            onUndeliveredElement = QueuedCommand::cancelWithoutExecution,
        )
    private val commandConsumer =
        serviceScope.launch(ioDispatcher) {
            for (command in commandQueue) {
                executeQueuedCommand(command)
            }
        }

    init {
        commandConsumer.invokeOnCompletion {
            commandQueue.cancel()
        }
    }

    fun onStartCommand(
        action: String?,
        startId: Int,
        transportFailoverRequestId: Long? = null,
        transportFailoverTarget: TransportFailoverTarget? = null,
    ): Int =
        when (action) {
            // null is a sticky restart after process death. Android's Always-on
            // controller starts VpnService with SERVICE_INTERFACE after boot.
            null,
            VpnService.SERVICE_INTERFACE,
            bootRecoveryStartAction,
            packageReplacedRecoveryStartAction,
            processDeathRecoveryStartAction,
            -> {
                enqueue(cancellableByUserStop = true) { onStartWithId(action, startId) }
                android.app.Service.START_STICKY
            }

            startAction -> {
                val prepareUserStart = shouldPrepareUserStart()
                onAcceptedStart()
                enqueue(cancellableByUserStop = true) {
                    if (prepareUserStart) beforeUserStart()
                    onStartWithId(action, startId)
                }
                android.app.Service.START_STICKY
            }

            diagnosticsStartAction -> {
                enqueue(cancellableByUserStop = true) { onStartWithId(action, startId) }
                android.app.Service.START_STICKY
            }

            transportFailoverRestartAction -> {
                enqueueTransportFailoverRestart(transportFailoverRequestId, transportFailoverTarget)
                android.app.Service.START_STICKY
            }

            startupFallbackStartAction -> {
                enqueue(cancellableByUserStop = true) { onStartWithId(action, startId) }
                android.app.Service.START_STICKY
            }

            stopAction, notificationStopAction -> {
                if (isStopAllowed(action)) {
                    onAcceptedStop()
                    cancelStartsAcceptedBeforeUserStop()
                    enqueue { onStop(startId) }
                    android.app.Service.START_NOT_STICKY
                } else {
                    Logger.w { "Ignoring stop action for $serviceLabel service while disconnect is blocked" }
                    enqueue(cancellableByUserStop = true, block = onStart)
                    android.app.Service.START_STICKY
                }
            }

            diagnosticsStopAction -> {
                if (isStopAllowed(action)) {
                    enqueue { onStop(startId) }
                    android.app.Service.START_NOT_STICKY
                } else {
                    Logger.w { "Ignoring diagnostics stop for $serviceLabel service while disconnect is blocked" }
                    enqueue(cancellableByUserStop = true, block = onStart)
                    android.app.Service.START_STICKY
                }
            }

            diagnosticsCompensatingStopAction -> {
                if (isStopAllowed(action) && isCompensatingStopCurrent()) {
                    enqueue { onStop(startId) }
                    android.app.Service.START_NOT_STICKY
                } else {
                    Logger.d { "Skipping stale diagnostics stop for $serviceLabel service" }
                    android.app.Service.START_STICKY
                }
            }

            else -> {
                Logger.w { "Unknown action for $serviceLabel service: $action" }
                android.app.Service.START_STICKY
            }
        }

    private fun enqueueTransportFailoverRestart(
        requestId: Long?,
        target: TransportFailoverTarget?,
    ) {
        if (requestId == null || target == null) {
            requestId?.let(transportFailoverCommandHandler.reject)
            Logger.w { "Ignoring transport failover restart without request identity" }
            return
        }
        enqueue(
            block = { transportFailoverCommandHandler.restart(requestId, target) },
            onDrop = { transportFailoverCommandHandler.reject(requestId) },
            cancellableByUserStop = true,
        )
    }

    fun onRevoke() {
        val revokeHandler = onRevoke ?: return
        enqueue(block = revokeHandler)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun executeCommand(block: suspend () -> Unit) {
        try {
            block()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            Logger.e(failure) { "$serviceLabel service command failed" }
        }
    }

    private suspend fun executeQueuedCommand(command: QueuedCommand) {
        val job =
            serviceScope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
                try {
                    executeCommand(command.block)
                } finally {
                    command.markExecuted()
                }
            }
        val superseded =
            synchronized(commandStateLock) {
                if (command.cancellableByUserStop && command.acceptedStopEpoch != acceptedStopEpoch) {
                    true
                } else {
                    activeCommand = ActiveCommand(command, job)
                    false
                }
            }
        if (superseded) {
            command.cancelWithoutExecution()
            job.cancel(CancellationException("$serviceLabel start superseded by user stop"))
            return
        }

        try {
            job.start()
            job.join()
        } finally {
            synchronized(commandStateLock) {
                if (activeCommand?.job === job) {
                    activeCommand = null
                }
            }
        }
    }

    private fun cancelStartsAcceptedBeforeUserStop() {
        val commandToCancel =
            synchronized(commandStateLock) {
                acceptedStopEpoch += 1
                activeCommand?.takeIf { it.command.cancellableByUserStop }
            }
        commandToCancel?.command?.cancelWithoutExecution()
        commandToCancel?.job?.cancel(CancellationException("$serviceLabel start superseded by user stop"))
    }

    private fun enqueue(
        onDrop: () -> Unit = {},
        cancellableByUserStop: Boolean = false,
        block: suspend () -> Unit,
    ) {
        val command =
            synchronized(commandStateLock) {
                QueuedCommand(block, onDrop, cancellableByUserStop, acceptedStopEpoch)
            }
        if (commandQueue.trySend(command).isFailure) {
            command.cancelWithoutExecution()
            Logger.w { "Dropping $serviceLabel service command after queue closure" }
        }
    }
}
