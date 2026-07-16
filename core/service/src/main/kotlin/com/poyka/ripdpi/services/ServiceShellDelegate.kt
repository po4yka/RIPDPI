package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.startAction
import com.poyka.ripdpi.data.stopAction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal const val notificationStopAction = "notification_stop"

internal class ServiceShellDelegate(
    private val serviceScope: CoroutineScope,
    private val serviceLabel: String,
    private val onStart: suspend () -> Unit,
    private val onStop: suspend (Int?) -> Unit,
    private val isStopAllowed: (String) -> Boolean = { true },
    private val onRevoke: (suspend () -> Unit)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun onStartCommand(
        action: String?,
        startId: Int,
    ): Int =
        when (action) {
            // null action indicates a sticky restart after process death.
            null, startAction -> {
                launchIo(onStart)
                android.app.Service.START_STICKY
            }

            stopAction, notificationStopAction -> {
                if (isStopAllowed(action)) {
                    launchIo { onStop(startId) }
                    android.app.Service.START_NOT_STICKY
                } else {
                    Logger.w { "Ignoring stop action for $serviceLabel service while disconnect is blocked" }
                    launchIo(onStart)
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
        launchIo(revokeHandler)
    }

    private fun launchIo(block: suspend () -> Unit) {
        serviceScope.launch(ioDispatcher) {
            block()
        }
    }
}
