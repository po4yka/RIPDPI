package com.poyka.ripdpi.activities

import com.poyka.ripdpi.R
import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.ServiceEvent
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.data.displayMessage
import com.poyka.ripdpi.platform.StringResolver
import com.poyka.ripdpi.services.ServiceController
import com.poyka.ripdpi.services.ServiceStartRejectionReason
import com.poyka.ripdpi.services.ServiceStartResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** Tracks a UI request until the existing service status or failure confirms its result. */
internal class ConfigRuntimeActions(
    private val scope: CoroutineScope,
    private val controller: ServiceController,
    private val service: ServiceStateStore,
    private val strings: StringResolver,
    private val isReconnectPending: () -> Boolean = { false },
) {
    private val mutableFeedback = MutableStateFlow(ConfigRuntimeFeedback())
    val feedback = mutableFeedback.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            service.status.collect { (status, _) ->
                if (status == AppStatus.Running) dismissError()
            }
        }
    }

    fun dismissError() {
        mutableFeedback.update { it.copy(error = null, requiresVpnPermission = false) }
    }

    fun toggle(
        mode: Mode,
        enabled: Boolean,
        beforeDispatch: suspend () -> Unit = {},
    ): Job? {
        if (!reserveOperation(enabled)) return null
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // Reserve intent order before waiting for an editor save, as the original action did.
                val startReceipt = if (enabled) controller.prepareStart(mode) else null
                val stopReceipt = if (enabled) null else controller.prepareStop()
                beforeDispatch()
                awaitDispatch(mode, enabled) {
                    if (enabled) {
                        when (val result = controller.startPrepared(mode, checkNotNull(startReceipt))) {
                            is ServiceStartResult.Accepted -> {
                                true
                            }

                            is ServiceStartResult.MaintenanceAccepted -> {
                                error("Expected a mode-bound start dispatch")
                            }

                            is ServiceStartResult.Rejected -> {
                                reject(result)
                                false
                            }
                        }
                    } else if (service.status.value == AppStatus.Running to mode) {
                        controller.stopPrepared(checkNotNull(stopReceipt))
                    } else {
                        false
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showError(strings.getString(R.string.update_error_unknown))
            } finally {
                mutableFeedback.update { it.copy(operation = null) }
            }
        }
    }

    private fun reserveOperation(enabled: Boolean): Boolean {
        if (!scope.isActive || isReconnectPending()) return false
        val previous = mutableFeedback.value
        val busy = previous.operation != null || service.status.value.first == AppStatus.Reconnecting
        val pending =
            ConfigRuntimeFeedback(
                operation = if (enabled) ConfigRuntimeOperation.Starting else ConfigRuntimeOperation.Stopping,
            )
        return !busy && mutableFeedback.compareAndSet(previous, pending)
    }

    private suspend fun awaitDispatch(
        mode: Mode,
        enabled: Boolean,
        dispatch: suspend () -> Boolean,
    ) = coroutineScope {
        // Subscribe before dispatch so a synchronous native failure cannot be lost.
        val failure =
            async(start = CoroutineStart.UNDISPATCHED) {
                service.events
                    .filterIsInstance<ServiceEvent.Failed>()
                    .first { it.modeAtFailure == null || it.modeAtFailure == mode }
            }
        try {
            if (!dispatch()) return@coroutineScope
            val ready =
                async(start = CoroutineStart.UNDISPATCHED) {
                    service.status.first { (status, runningMode) ->
                        if (enabled) status == AppStatus.Running && runningMode == mode else status == AppStatus.Halted
                    }
                }
            try {
                val result =
                    withTimeoutOrNull(30.seconds) {
                        select {
                            failure.onAwait { event ->
                                showError(event.reason.displayMessage)
                                false
                            }
                            ready.onAwait { true }
                        }
                    }
                if (result == null) {
                    showError(
                        strings.getString(
                            if (enabled) R.string.connection_timed_out else R.string.runtime_config_stop_timeout,
                        ),
                    )
                }
            } finally {
                ready.cancel()
            }
        } finally {
            failure.cancel()
        }
    }

    private fun reject(result: ServiceStartResult.Rejected) {
        val senderName = result.mode.startSenderName(strings)
        val message =
            strings.getString(R.string.failed_to_start, senderName) + ": " + result.reason.displayMessage(strings)
        mutableFeedback.update {
            it.copy(
                error = message,
                requiresVpnPermission =
                    result.reason == ServiceStartRejectionReason.VpnConsentMissing,
            )
        }
    }

    private fun showError(message: String) {
        mutableFeedback.update { it.copy(error = message, requiresVpnPermission = false) }
    }
}
