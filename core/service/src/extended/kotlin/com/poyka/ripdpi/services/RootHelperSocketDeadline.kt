package com.poyka.ripdpi.services

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

private val rootHelperDeadlineScheduler =
    ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "root-helper-ipc-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

internal fun rootHelperRemainingMillis(deadlineNanos: Long): Int {
    val remaining = deadlineNanos - System.nanoTime()
    if (remaining <= 0L) throw SocketTimeoutException("Root-helper socket deadline expired")
    return TimeUnit.NANOSECONDS
        .toMillis(remaining)
        .coerceIn(1L, Int.MAX_VALUE.toLong())
        .toInt()
}

internal fun <T> withRootHelperSocket(
    socketPath: String,
    deadlineNanos: Long,
    exchange: (LocalSocket) -> T,
): T =
    LocalSocket().use { socket ->
        // The stream getter creates the fd. SO_TIMEOUT also sets SO_SNDTIMEO, which bounds Unix connect.
        socket.inputStream
        socket.soTimeout = rootHelperRemainingMillis(deadlineNanos)
        withRootHelperDeadline(
            deadlineNanos,
            abort = {
                runCatching { socket.shutdownInput() }
                runCatching { socket.shutdownOutput() }
                runCatching { socket.close() }
            },
        ) {
            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            exchange(socket)
        }
    }

/** Per-socket timeouts do not bound a peer that keeps sending a partial frame. */
internal fun <T> withRootHelperDeadline(
    deadlineNanos: Long,
    abort: () -> Unit,
    operation: () -> T,
): T {
    val lock = Any()
    var finished = false
    var expired = false
    val remainingNanos = deadlineNanos - System.nanoTime()
    if (remainingNanos <= 0L) throw SocketTimeoutException("Root-helper socket deadline expired")
    val timeout =
        rootHelperDeadlineScheduler.schedule(
            {
                synchronized(lock) {
                    if (!finished) {
                        expired = true
                        runCatching(abort)
                    }
                }
            },
            remainingNanos,
            TimeUnit.NANOSECONDS,
        )

    fun deadlineExpired(): Boolean = synchronized(lock) { expired || System.nanoTime() >= deadlineNanos }
    try {
        val result =
            try {
                Result.success(operation())
            } catch (error: IOException) {
                Result.failure(error)
            }
        if (deadlineExpired()) {
            val timeoutFailure = SocketTimeoutException("Root-helper socket deadline expired")
            result.exceptionOrNull()?.let(timeoutFailure::initCause)
            throw timeoutFailure
        }
        return result.getOrThrow()
    } finally {
        // Join an in-flight abort before the caller can close and reuse the descriptor.
        synchronized(lock) { finished = true }
        timeout.cancel(false)
    }
}
