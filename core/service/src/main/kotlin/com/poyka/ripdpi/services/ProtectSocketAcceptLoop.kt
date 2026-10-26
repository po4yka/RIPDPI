package com.poyka.ripdpi.services

import co.touchlab.kermit.Logger
import java.io.IOException

private val acceptLog = Logger.withTag("ProtectSocket")

private const val AcceptRetryInitialDelayMs = 50L
private const val AcceptRetryMaxDelayMs = 1_000L

/**
 * Accepts protect clients until [isRunning] turns false.
 *
 * A persistent accept() failure (EMFILE under fd exhaustion) must not spin a core, so each
 * consecutive failure waits twice as long as the previous one, up to one second, and only the
 * failures that change the wait are logged. A successful accept resets the wait.
 */
internal fun runProtectAcceptLoop(
    isRunning: () -> Boolean,
    accept: () -> ProtectSocketClientSession,
    dispatch: (ProtectSocketClientSession) -> Boolean,
    pause: (Long) -> Unit = Thread::sleep,
) {
    var retryDelayMs = AcceptRetryInitialDelayMs
    while (isRunning()) {
        try {
            val client = accept()
            retryDelayMs = AcceptRetryInitialDelayMs
            if (!dispatch(client)) {
                acceptLog.w { "protect socket rejected client due to shutdown or back-pressure" }
            }
        } catch (e: IOException) {
            if (!isRunning()) return
            if (retryDelayMs < AcceptRetryMaxDelayMs) {
                acceptLog.w(e) { "protect socket accept error; retrying in $retryDelayMs ms" }
            }
            try {
                pause(retryDelayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(AcceptRetryMaxDelayMs)
        }
    }
}
