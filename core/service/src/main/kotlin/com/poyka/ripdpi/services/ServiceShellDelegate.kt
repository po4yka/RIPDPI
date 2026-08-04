package com.poyka.ripdpi.services

import android.net.VpnService
import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.startAction
import com.poyka.ripdpi.data.stopAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal const val notificationStopAction = "notification_stop"
internal const val diagnosticsStopAction = "diagnostics_stop"
internal const val diagnosticsStartAction = "diagnostics_start"
internal const val diagnosticsCompensatingStopAction = "diagnostics_compensating_stop"
internal const val transportFailoverRestartAction = "transport_failover_restart"
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

internal class ServiceShellDelegate(
    private val serviceScope: CoroutineScope,
    private val serviceLabel: String,
    private val onStart: suspend () -> Unit,
    private val onStartWithId: suspend (String?, Int) -> Unit = { _, _ -> onStart() },
    private val onStop: suspend (Int?) -> Unit,
    private val onTransportFailoverRestart: suspend () -> Unit = onStart,
    private val beforeUserStart: suspend () -> Unit = {},
    private val shouldPrepareUserStart: () -> Boolean = { true },
    private val isStopAllowed: (String) -> Boolean = { true },
    private val onAcceptedStart: () -> Unit = {},
    private val onAcceptedStop: () -> Unit = {},
    private val isCompensatingStopCurrent: () -> Boolean = { true },
    private val onRevoke: (suspend () -> Unit)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val commandQueue = Channel<suspend () -> Unit>(capacity = Channel.UNLIMITED)

    init {
        serviceScope.launch(ioDispatcher) {
            for (command in commandQueue) {
                executeCommand(command)
            }
        }
    }

    fun onStartCommand(
        action: String?,
        startId: Int,
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
                enqueue { onStartWithId(action, startId) }
                android.app.Service.START_STICKY
            }

            startAction -> {
                val prepareUserStart = shouldPrepareUserStart()
                onAcceptedStart()
                enqueue {
                    if (prepareUserStart) beforeUserStart()
                    onStartWithId(action, startId)
                }
                android.app.Service.START_STICKY
            }

            diagnosticsStartAction -> {
                enqueue { onStartWithId(action, startId) }
                android.app.Service.START_STICKY
            }

            transportFailoverRestartAction -> {
                enqueue(onTransportFailoverRestart)
                android.app.Service.START_STICKY
            }

            startupFallbackStartAction -> {
                enqueue { onStartWithId(action, startId) }
                android.app.Service.START_STICKY
            }

            stopAction, notificationStopAction -> {
                if (isStopAllowed(action)) {
                    onAcceptedStop()
                    enqueue { onStop(startId) }
                    android.app.Service.START_NOT_STICKY
                } else {
                    Logger.w { "Ignoring stop action for $serviceLabel service while disconnect is blocked" }
                    enqueue(onStart)
                    android.app.Service.START_STICKY
                }
            }

            diagnosticsStopAction -> {
                if (isStopAllowed(action)) {
                    enqueue { onStop(startId) }
                    android.app.Service.START_NOT_STICKY
                } else {
                    Logger.w { "Ignoring diagnostics stop for $serviceLabel service while disconnect is blocked" }
                    enqueue(onStart)
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

    fun onRevoke() {
        val revokeHandler = onRevoke ?: return
        enqueue(revokeHandler)
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

    private fun enqueue(block: suspend () -> Unit) {
        if (commandQueue.trySend(block).isFailure) {
            Logger.w { "Dropping $serviceLabel service command after queue closure" }
        }
    }
}
