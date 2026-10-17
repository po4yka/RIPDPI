package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.AppStatus
import com.poyka.ripdpi.data.Mode
import com.poyka.ripdpi.data.RuntimeActivationReceipt
import com.poyka.ripdpi.data.ServiceStateStore
import com.poyka.ripdpi.services.ServiceController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Consumes feedback for one explicit start after its receipt and actual Running status agree. */
internal class ConnectionResultFeedback(
    private val controller: ServiceController,
    private val service: ServiceStateStore,
    private val hapticsEnabled: () -> Boolean,
    private val onConnected: () -> Unit,
) {
    class Attempt(
        val mode: Mode,
        var receipt: RuntimeActivationReceipt? = null,
        var observedRunning: Boolean = false,
    )

    private var pending: Attempt? = null

    fun begin(mode: Mode): Attempt {
        val attempt = Attempt(mode)
        // Restored Running is quiet; the inactive mode can still have its own explicit start.
        pending = attempt.takeUnless { service.status.value == AppStatus.Running to mode }
        return attempt
    }

    fun clear(attempt: Attempt? = null) {
        if (attempt == null || pending === attempt) pending = null
    }

    suspend fun accepted(
        attempt: Attempt,
        receipt: RuntimeActivationReceipt,
    ) {
        attempt.receipt = receipt
        complete(attempt)
    }

    suspend fun observedRunning(mode: Mode) {
        pending?.takeIf { it.mode == mode }?.let { attempt ->
            attempt.observedRunning = true
            complete(attempt)
        }
    }

    private suspend fun complete(attempt: Attempt) {
        val receipt = attempt.receipt
        if (receipt == null || pending !== attempt || !attempt.observedRunning) return
        val authority =
            try {
                controller.captureRuntimeSnapshot()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                clear(attempt)
                null
            }
        currentCoroutineContext().ensureActive()
        if (pending !== attempt) return
        pending = null
        if (authority != null) {
            val stillRunning = service.status.value == AppStatus.Running to attempt.mode
            val stillCurrent =
                authority.reference == receipt.authority && authority.command?.commandId == receipt.commandId
            if (stillRunning && stillCurrent && hapticsEnabled()) onConnected()
        }
    }
}
