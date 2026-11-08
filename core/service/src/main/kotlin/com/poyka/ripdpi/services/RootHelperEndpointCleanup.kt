package com.poyka.ripdpi.services

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val EndpointExitTimeoutMillis = 1_000L
private const val EndpointExitPollMillis = 25L

/** Keep the old nonce and socket until their authenticated owner releases the endpoint. */
internal fun shutdownRootHelperEndpoint(
    socket: File,
    nonceFile: File,
    requestShutdown: (String, String?, Long) -> Unit = RootHelperIpc::shutdownBefore,
    endpointIsClosed: (File, Long) -> Boolean = ::rootHelperEndpointIsClosed,
    timeoutMillis: Long = EndpointExitTimeoutMillis,
) {
    val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    if (endpointIsClosed(socket, deadlineNanos)) return
    val shutdownFailure =
        runCatching { requestShutdown(socket.absolutePath, nonceFile.absolutePath, deadlineNanos) }.exceptionOrNull()
    if (shutdownFailure != null) {
        if (!endpointIsClosed(socket, deadlineNanos)) throw RuntimeCleanupPendingException(shutdownFailure)
        return
    }
    awaitRootHelperEndpointExit(socket, deadlineNanos, endpointIsClosed)
}

private fun awaitRootHelperEndpointExit(
    socket: File,
    deadlineNanos: Long,
    endpointIsClosed: (File, Long) -> Boolean,
) {
    while (!endpointIsClosed(socket, deadlineNanos)) {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) {
            throw RuntimeCleanupPendingException(IOException("Root-helper endpoint did not close after shutdown"))
        }
        try {
            Thread.sleep(TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceIn(1L, EndpointExitPollMillis))
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeCleanupPendingException(error)
        }
    }
}

private fun rootHelperEndpointIsClosed(
    path: File,
    deadlineNanos: Long,
): Boolean {
    if (rootHelperEndpointMissing(path)) return true
    return try {
        withRootHelperSocket(path.absolutePath, deadlineNanos) { false }
    } catch (error: IOException) {
        if (!rootHelperConnectRefused(error)) throw RuntimeCleanupPendingException(error)
        true
    } catch (error: SecurityException) {
        throw RuntimeCleanupPendingException(error)
    }
}

private fun rootHelperEndpointMissing(path: File): Boolean =
    try {
        Os.lstat(path.absolutePath)
        false
    } catch (error: ErrnoException) {
        if (error.errno != OsConstants.ENOENT) throw RuntimeCleanupPendingException(error)
        true
    }

internal fun rootHelperConnectRefused(error: IOException): Boolean {
    val errno = (error.cause as? ErrnoException)?.errno
    if (errno != null) return errno == OsConstants.ECONNREFUSED || errno == OsConstants.ENOENT
    // LocalSocket JNI reports errno through jniThrowIOException rather than an ErrnoException cause.
    val message = error.message
    return message != null &&
        (message == Os.strerror(OsConstants.ECONNREFUSED) || message == Os.strerror(OsConstants.ENOENT))
}
