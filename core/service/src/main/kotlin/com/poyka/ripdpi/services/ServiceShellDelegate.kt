package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.data.START_ACTION
import com.poyka.ripdpi.data.STOP_ACTION
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class ServiceShellDelegate(
    private val serviceScope: CoroutineScope,
    private val serviceLabel: String,
    private val onStart: suspend () -> Unit,
    private val onStop: suspend (Int?) -> Unit,
    private val onRevoke: (suspend () -> Unit)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun onStartCommand(
        action: String?,
        startId: Int,
    ): Int =
        when (action) {
            // null action indicates a sticky restart after process death.
            null, START_ACTION -> {
                launchIo(onStart)
                android.app.Service.START_STICKY
            }

            STOP_ACTION -> {
                launchIo { onStop(startId) }
                android.app.Service.START_NOT_STICKY
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
