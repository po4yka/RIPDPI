package com.poyka.ripdpi.services

import android.content.Intent
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

const val explicitUserIntentGenerationExtra = "explicit_user_intent_generation"
const val vpnStartGenerationExtra = "vpn_start_generation"

internal fun Intent?.vpnStartGeneration(): Long? =
    this?.takeIf { it.hasExtra(vpnStartGenerationExtra) }?.getLongExtra(vpnStartGenerationExtra, -1L)

internal fun Intent?.explicitUserIntentGeneration(): Long? =
    this
        ?.takeIf {
            it.hasExtra(
                explicitUserIntentGenerationExtra,
            )
        }?.getLongExtra(explicitUserIntentGenerationExtra, -1L)

internal const val notificationStopAction = "notification_stop"
internal const val diagnosticsStopAction = "diagnostics_stop"
internal const val diagnosticsStartAction = "diagnostics_start"
internal const val diagnosticsCompensatingStopAction = "diagnostics_compensating_stop"
internal const val transportFailoverRestartAction = "transport_failover_restart"
internal const val transportActivationStartAction = "transport_activation_start"
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
    val activate: (suspend (Long, TransportFailoverTarget) -> Unit)? = null,
)

internal class ServiceShellIntentCallbacks(
    val acceptedStart: () -> Unit = {},
    val acceptedStop: (Long?) -> Unit = {},
)

internal class ServiceShellDelegate(
    private val serviceScope: CoroutineScope,
    private val serviceIntentArbiter: ServiceIntentArbiter,
    private val serviceLabel: String,
    private val onStart: suspend () -> Unit,
    private val onStartWithId: suspend (String?, Int) -> Unit = { _, _ -> onStart() },
    private val onStop: suspend (Int?, ServiceStopProvenance) -> Unit,
    private val transportFailoverCommandHandler: TransportFailoverCommandHandler =
        TransportFailoverCommandHandler(restart = { _, _ -> onStart() }),
    private val beforeUserStart: suspend (ExplicitUserStartGuard) -> Unit = {},
    private val shouldPrepareUserStart: () -> Boolean = { true },
    private val isStopAllowed: (String) -> Boolean = { true },
    private val intentCallbacks: ServiceShellIntentCallbacks = ServiceShellIntentCallbacks(),
    private val isCompensatingStopCurrent: () -> Boolean = { true },
    private val onRevoke: (suspend () -> Unit)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private class QueuedCommand(
        val block: suspend () -> Unit,
        val onDrop: () -> Unit,
        val onCompletion: () -> Unit,
        val cancellableByUserStop: Boolean,
        val acceptedStopEpoch: Long,
    ) {
        private val dispositionClaimed = AtomicBoolean(false)
        private val executionStarted = AtomicBoolean(false)
        private val completionClaimed = AtomicBoolean(false)

        fun markStarted() {
            executionStarted.set(true)
        }

        fun completeStart() {
            if (completionClaimed.compareAndSet(false, true)) onCompletion()
        }

        fun markExecuted() {
            dispositionClaimed.compareAndSet(false, true)
            completeStart()
        }

        fun cancelWithoutExecution() {
            if (dispositionClaimed.compareAndSet(false, true)) {
                try {
                    onDrop()
                } finally {
                    if (!executionStarted.get()) completeStart()
                }
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
        explicitUserIntentGeneration: Long? = null,
        vpnStartGeneration: Long? = null,
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
                enqueueInitialStart(action, startId, vpnStartGeneration)
                android.app.Service.START_STICKY
            }

            startAction -> {
                enqueueExplicitUserStart(action, startId, explicitUserIntentGeneration, vpnStartGeneration)
                android.app.Service.START_STICKY
            }

            diagnosticsStartAction -> {
                enqueueInitialStart(action, startId, vpnStartGeneration)
                android.app.Service.START_STICKY
            }

            transportFailoverRestartAction -> {
                enqueueTransportFailoverRestart(
                    transportFailoverRequestId,
                    transportFailoverTarget,
                    vpnStartGeneration = vpnStartGeneration,
                )
                android.app.Service.START_STICKY
            }

            transportActivationStartAction -> {
                val guard = acceptExplicitUserStart(explicitUserIntentGeneration)
                if (guard == null) {
                    transportFailoverRequestId?.let(transportFailoverCommandHandler.reject)
                    vpnStartGeneration?.let(serviceIntentArbiter::completeVpnStart)
                } else {
                    enqueueTransportFailoverRestart(
                        transportFailoverRequestId,
                        transportFailoverTarget,
                        guard,
                        vpnStartGeneration,
                    )
                }
                android.app.Service.START_STICKY
            }

            startupFallbackStartAction -> {
                enqueueInitialStart(action, startId, vpnStartGeneration)
                android.app.Service.START_STICKY
            }

            stopAction, notificationStopAction -> {
                enqueueUserStop(action, startId, explicitUserIntentGeneration)
            }

            diagnosticsStopAction -> {
                if (isStopAllowed(action)) {
                    enqueue {
                        stopWithProvenance(startId, ServiceStopProvenance.DiagnosticsRawPathScan)
                    }
                    android.app.Service.START_NOT_STICKY
                } else {
                    Logger.w { "Ignoring diagnostics stop for $serviceLabel service while disconnect is blocked" }
                    enqueue(cancellableByUserStop = true, block = onStart)
                    android.app.Service.START_STICKY
                }
            }

            diagnosticsCompensatingStopAction -> {
                if (isStopAllowed(action) && isCompensatingStopCurrent()) {
                    enqueue {
                        stopWithProvenance(startId, ServiceStopProvenance.DiagnosticsCompensation)
                    }
                    android.app.Service.START_NOT_STICKY
                } else {
                    Logger.d { "Skipping stale diagnostics stop for $serviceLabel service" }
                    android.app.Service.START_STICKY
                }
            }

            else -> {
                Logger.w { "Unknown action for $serviceLabel service: $action" }
                vpnStartGeneration?.let(serviceIntentArbiter::completeVpnStart)
                android.app.Service.START_STICKY
            }
        }

    private fun enqueueInitialStart(
        action: String?,
        startId: Int,
        vpnStartGeneration: Long?,
    ) {
        enqueue(cancellableByUserStop = true, vpnStartGeneration = vpnStartGeneration) {
            onStartWithId(action, startId)
        }
    }

    private fun enqueueUserStop(
        action: String,
        startId: Int,
        generation: Long?,
    ): Int {
        if (!isStopAllowed(action)) {
            Logger.w { "Ignoring stop action for $serviceLabel service while disconnect is blocked" }
            enqueue(cancellableByUserStop = true, block = onStart)
            return android.app.Service.START_STICKY
        }
        val acceptedGuard =
            serviceIntentArbiter.serialize {
                if (action == stopAction && (
                        generation == null ||
                            !serviceIntentArbiter.explicitUserStartGuard(generation).isCurrent()
                    )
                ) {
                    null
                } else {
                    intentCallbacks.acceptedStop(if (action == stopAction) generation else null)
                    cancelStartsAcceptedBeforeUserStop()
                    serviceIntentArbiter.explicitUserStartGuard(
                        serviceIntentArbiter.captureExplicitUserIntentGeneration(),
                    )
                }
            }
        return if (acceptedGuard != null) {
            val provenance =
                if (action == notificationStopAction) {
                    ServiceStopProvenance.NotificationAction
                } else {
                    ServiceStopProvenance.UserRequest
                }
            enqueue {
                if (acceptedGuard.isCurrent()) stopWithProvenance(startId, provenance)
            }
            android.app.Service.START_NOT_STICKY
        } else {
            android.app.Service.START_STICKY
        }
    }

    private fun acceptExplicitUserStart(generation: Long?): ExplicitUserStartGuard? {
        if (generation == null) return null
        val guard = serviceIntentArbiter.explicitUserStartGuard(generation)
        return guard.takeIf { it.runIfCurrent(intentCallbacks.acceptedStart) }
    }

    private fun enqueueExplicitUserStart(
        action: String,
        startId: Int,
        generation: Long?,
        vpnStartGeneration: Long? = null,
    ) {
        val guard = acceptExplicitUserStart(generation)
        if (guard == null) {
            vpnStartGeneration?.let(serviceIntentArbiter::completeVpnStart)
            return
        }
        val prepareUserStart = shouldPrepareUserStart()
        enqueue(cancellableByUserStop = true, vpnStartGeneration = vpnStartGeneration) {
            if (guard.isCurrent()) {
                if (prepareUserStart) beforeUserStart(guard)
                if (guard.isCurrent()) onStartWithId(action, startId)
            }
        }
    }

    private suspend fun stopWithProvenance(
        startId: Int,
        provenance: ServiceStopProvenance,
    ) {
        onStop(startId, provenance)
    }

    private fun enqueueTransportFailoverRestart(
        requestId: Long?,
        target: TransportFailoverTarget?,
        explicitGuard: ExplicitUserStartGuard? = null,
        vpnStartGeneration: Long? = null,
    ) {
        if (requestId == null || target == null) {
            requestId?.let(transportFailoverCommandHandler.reject)
            Logger.w { "Ignoring transport failover restart without request identity" }
            vpnStartGeneration?.let(serviceIntentArbiter::completeVpnStart)
            return
        }
        val apply =
            if (explicitGuard !=
                null
            ) {
                transportFailoverCommandHandler.activate
            } else {
                transportFailoverCommandHandler.restart
            }
        if (apply == null) {
            transportFailoverCommandHandler.reject(requestId)
            vpnStartGeneration?.let(serviceIntentArbiter::completeVpnStart)
            return
        }
        enqueue(
            block = {
                if (explicitGuard == null || explicitGuard.isCurrent()) {
                    apply(requestId, target)
                } else {
                    transportFailoverCommandHandler.reject(requestId)
                }
            },
            onDrop = { transportFailoverCommandHandler.reject(requestId) },
            cancellableByUserStop = true,
            vpnStartGeneration = vpnStartGeneration,
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
            command.markStarted()
            job.start()
            job.join()
        } finally {
            command.completeStart()
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
        vpnStartGeneration: Long? = null,
        block: suspend () -> Unit,
    ) {
        val command =
            synchronized(commandStateLock) {
                QueuedCommand(
                    block = block,
                    onDrop = onDrop,
                    onCompletion = { vpnStartGeneration?.let(serviceIntentArbiter::completeVpnStart) },
                    cancellableByUserStop = cancellableByUserStop,
                    acceptedStopEpoch = acceptedStopEpoch,
                )
            }
        if (commandQueue.trySend(command).isFailure) {
            command.cancelWithoutExecution()
            Logger.w { "Dropping $serviceLabel service command after queue closure" }
        }
    }
}
