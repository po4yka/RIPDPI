package com.poyka.ripdpi.proxyimport

import android.content.res.AssetFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val ImportPollSliceMillis = 100L
private const val ImportDeadlineWorkers = 2

/** Reads only the asset slice; closing the channel interrupts even a blocking pipe read. */
internal fun readImportDescriptor(
    asset: AssetFileDescriptor,
    deadlineNanos: Long,
    cancelled: () -> Boolean,
): String? {
    val length = asset.declaredLength
    if (length > MaxImportFileBytes || cancelled()) return null
    val capacity = if (length >= 0L) length.toInt() else MaxImportFileBytes + 1
    return try {
        rewindImportDescriptor(asset)
        asset.createInputStream().use { stream ->
            val channel = stream.channel
            val closeTask = ImportReadDeadlines.watch(channel, deadlineNanos, cancelled)
            try {
                readImportChannel(channel, asset, capacity, deadlineNanos, cancelled)
            } finally {
                closeTask.cancel(false)
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: ErrnoException) {
        null
    }
}

private fun rewindImportDescriptor(asset: AssetFileDescriptor) {
    try {
        // createInputStream skips the asset offset relative to the current file position.
        Os.lseek(asset.fileDescriptor, 0, OsConstants.SEEK_SET)
    } catch (error: ErrnoException) {
        if (error.errno != OsConstants.ESPIPE || asset.startOffset != 0L) throw error
    }
}

private object ImportReadDeadlines {
    // Each of the two admitted read workers owns at most one task; completion removes it.
    // Keep channel closure independent of provider cancellation, which can block in Binder.
    private val executor =
        ScheduledThreadPoolExecutor(ImportDeadlineWorkers) { runnable ->
            Thread(runnable, "ripdpi-import-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

    fun watch(
        channel: FileChannel,
        deadlineNanos: Long,
        cancelled: () -> Boolean,
    ) = executor.scheduleAtFixedRate(
        { if (cancelled() || System.nanoTime() >= deadlineNanos) runCatching { channel.close() } },
        0L,
        ImportPollSliceMillis,
        TimeUnit.MILLISECONDS,
    )
}

private fun readImportChannel(
    channel: FileChannel,
    asset: AssetFileDescriptor,
    capacity: Int,
    deadlineNanos: Long,
    cancelled: () -> Boolean,
): String? {
    val buffer = ByteBuffer.allocate(capacity)
    val poll =
        StructPollfd().apply {
            fd = asset.fileDescriptor
            events = (OsConstants.POLLIN or OsConstants.POLLHUP).toShort()
        }
    var readCount = 0
    while (buffer.hasRemaining() && readCount != -1) {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (cancelled() || remainingNanos <= 0L) break
        readCount = channel.read(buffer)
        // Providers may supply a nonblocking descriptor or change its flags while we read.
        if (readCount == 0) {
            val waitMillis =
                TimeUnit.NANOSECONDS
                    .toMillis(remainingNanos)
                    .coerceIn(1L, ImportPollSliceMillis)
                    .toInt()
            waitForImportBytes(poll, waitMillis)
        }
    }
    return if (!cancelled() && System.nanoTime() < deadlineNanos) {
        decodeImportText(buffer.array().copyOf(buffer.position()))
    } else {
        null
    }
}

private fun waitForImportBytes(
    poll: StructPollfd,
    waitMillis: Int,
) {
    try {
        Os.poll(arrayOf(poll), waitMillis)
    } catch (error: ErrnoException) {
        if (error.errno != OsConstants.EINTR) throw error
    }
}
