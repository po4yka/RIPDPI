package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class LogcatSnapshot(
    val content: String,
    val captureScope: String,
    val byteCount: Int,
    val truncated: Boolean = false,
)

open class LogcatSnapshotCollector
    @Inject
    constructor() {
        companion object {
            const val AppVisibleSnapshotScope = "app_visible_snapshot"
            const val TimeBoundSnapshotScope = "time_bound_snapshot"
            const val MAX_LOGCAT_BYTES = 512 * 1024
            private const val READ_BUFFER_CHARS = 8192
        }

        /**
         * Capture logcat for the app process.
         *
         * @param sinceTimestampMs If provided, captures logs starting from this epoch
         *   timestamp using logcat's `-T` flag. This ensures logs from long-running
         *   diagnostic scans (300s+) are not lost to the circular buffer rotation that
         *   occurs between scan start and archive export.
         */
        open suspend fun capture(sinceTimestampMs: Long? = null): LogcatSnapshot? =
            withContext(Dispatchers.IO) {
                val output =
                    try {
                        readLogcatOutput(sinceTimestampMs)
                    } catch (error: IOException) {
                        Logger.e(error) { "Failed to collect logs" }
                        null
                    } catch (error: SecurityException) {
                        Logger.e(error) { "Failed to collect logs" }
                        null
                    }

                if (output == null || output.content.isBlank()) {
                    null
                } else {
                    val scope =
                        if (sinceTimestampMs != null) TimeBoundSnapshotScope else AppVisibleSnapshotScope
                    LogcatSnapshot(
                        content = output.content,
                        captureScope = scope,
                        byteCount = output.byteCount,
                        truncated = output.truncated,
                    )
                }
            }

        /**
         * Filter to app's own PID to avoid capturing logs from other apps.
         * When [sinceTimestampMs] is provided, uses `-T` to capture logs from that
         * point forward instead of relying on the current circular buffer contents.
         */
        protected open fun readLogcatOutput(sinceTimestampMs: Long? = null): LogcatReadOutput {
            val command = buildLogcatCommand(android.os.Process.myPid(), sinceTimestampMs)
            val process = Runtime.getRuntime().exec(command.toTypedArray())
            try {
                process.errorStream.close()
                return process.inputStream.bufferedReader().use { reader ->
                    if (sinceTimestampMs == null) {
                        readTailBounded(reader)
                    } else {
                        readHeadAndTailBounded(reader)
                    }
                }
            } finally {
                process.destroy()
                process.waitFor()
            }
        }

        private fun readTailBounded(reader: java.io.BufferedReader): LogcatReadOutput {
            val buffer = RollingByteTail(MAX_LOGCAT_BYTES)
            val charBuf = CharArray(READ_BUFFER_CHARS)
            var charsRead = reader.read(charBuf)
            while (charsRead != -1) {
                val chunk = String(charBuf, 0, charsRead)
                buffer.append(chunk.toByteArray(Charsets.UTF_8))
                charsRead = reader.read(charBuf)
            }
            val bytes = buffer.toByteArray()
            return LogcatReadOutput(
                content = bytes.toString(Charsets.UTF_8),
                byteCount = bytes.size,
                truncated = buffer.truncated,
            )
        }

        private fun readHeadAndTailBounded(reader: java.io.BufferedReader): LogcatReadOutput {
            val buffer = RollingByteHeadAndTail(MAX_LOGCAT_BYTES)
            val charBuf = CharArray(READ_BUFFER_CHARS)
            var charsRead = reader.read(charBuf)
            while (charsRead != -1) {
                buffer.append(String(charBuf, 0, charsRead).toByteArray(Charsets.UTF_8))
                charsRead = reader.read(charBuf)
            }
            val bytes = buffer.toByteArray()
            return LogcatReadOutput(
                content = bytes.toString(Charsets.UTF_8),
                byteCount = bytes.size,
                truncated = buffer.truncated,
            )
        }
    }

data class LogcatReadOutput(
    val content: String,
    val byteCount: Int,
    val truncated: Boolean,
)

internal fun buildLogcatCommand(
    pid: Int,
    sinceTimestampMs: Long?,
): List<String> =
    buildList {
        add("logcat")
        add("--pid=$pid")
        add("-d")
        if (sinceTimestampMs != null) {
            add("-T")
            add(SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(sinceTimestampMs)))
        }
        // Keep archive collection limited to this app process; no unrestricted logcat fallback.
        add("-s")
        add("ripdpi-native:V")
        add("ripdpi:V")
        add("AndroidRuntime:E")
        add("*:W")
    }

internal const val Utf8ContinuationMask = 0xC0
internal const val Utf8ContinuationTag = 0x80
private const val Utf8ByteMask = 0xFF
private const val Utf8TwoBytePrefixMask = 0xE0
private const val Utf8TwoBytePrefix = 0xC0
private const val Utf8ThreeBytePrefixMask = 0xF0
private const val Utf8ThreeBytePrefix = 0xE0
private const val Utf8FourBytePrefixMask = 0xF8
private const val Utf8FourBytePrefix = 0xF0
private const val Utf8SingleByteLength = 1
private const val Utf8TwoByteLength = 2
private const val Utf8ThreeByteLength = 3
private const val Utf8FourByteLength = 4

private class RollingByteTail(
    private val capacity: Int,
) {
    private var bytes = ByteArray(0)
    var truncated: Boolean = false
        private set

    fun append(chunk: ByteArray) {
        bytes =
            when {
                chunk.size >= capacity -> {
                    truncated = truncated || bytes.isNotEmpty() || chunk.size > capacity
                    chunk.copyOfRange(chunk.size - capacity, chunk.size)
                }

                bytes.size + chunk.size <= capacity -> {
                    bytes + chunk
                }

                else -> {
                    truncated = true
                    val keep = capacity - chunk.size
                    bytes.copyOfRange(bytes.size - keep, bytes.size) + chunk
                }
            }
    }

    fun toByteArray(): ByteArray {
        var start = 0
        while (start < bytes.size && bytes[start].toInt() and Utf8ContinuationMask == Utf8ContinuationTag) {
            start += 1
        }
        return bytes.copyOfRange(start, bytes.size)
    }
}

internal const val LogcatTruncationMarker = "\n[logcat truncated: head and tail retained]\n"

internal fun headAndTailUtf8Bytes(
    value: String,
    maxBytes: Int,
    truncationMarker: String = LogcatTruncationMarker,
): ByteArray =
    RollingByteHeadAndTail(maxBytes, truncationMarker.toByteArray(Charsets.UTF_8))
        .apply { append(value.toByteArray(Charsets.UTF_8)) }
        .toByteArray()

private class RollingByteHeadAndTail(
    private val capacity: Int,
    private val truncationMarker: ByteArray = LogcatTruncationMarker.toByteArray(Charsets.UTF_8),
) {
    private var completeBytes = ByteArray(0)
    private var headBytes = ByteArray(0)
    private var tail = RollingByteTail(0)
    var truncated: Boolean = false
        private set

    fun append(chunk: ByteArray) {
        if (!truncated && completeBytes.size + chunk.size <= capacity) {
            completeBytes += chunk
            return
        }
        if (!truncated) {
            truncated = true
            val combined = completeBytes + chunk
            val available = (capacity - truncationMarker.size).coerceAtLeast(0)
            val headCapacity = available / 2
            headBytes = headUtf8Bytes(combined, headCapacity)
            tail = RollingByteTail(available - headBytes.size)
            tail.append(combined.copyOfRange(headBytes.size, combined.size))
            completeBytes = ByteArray(0)
        } else {
            tail.append(chunk)
        }
    }

    fun toByteArray(): ByteArray =
        if (!truncated) {
            completeBytes
        } else {
            headBytes + truncationMarker + tail.toByteArray()
        }
}

private fun headUtf8Bytes(
    bytes: ByteArray,
    maxBytes: Int,
): ByteArray {
    if (maxBytes <= 0 || bytes.size <= maxBytes) {
        return if (maxBytes <= 0) byteArrayOf() else bytes
    }
    var end = maxBytes
    var continuationBytes = 0
    while (end - continuationBytes > 0 &&
        bytes[end - continuationBytes - 1].toInt() and Utf8ContinuationMask == Utf8ContinuationTag
    ) {
        continuationBytes += 1
    }
    val leadingIndex = end - continuationBytes - 1
    if (leadingIndex >= 0) {
        val leadingByte = bytes[leadingIndex].toInt() and Utf8ByteMask
        val expectedLength =
            when {
                leadingByte and Utf8ContinuationTag == 0 -> Utf8SingleByteLength
                leadingByte and Utf8TwoBytePrefixMask == Utf8TwoBytePrefix -> Utf8TwoByteLength
                leadingByte and Utf8ThreeBytePrefixMask == Utf8ThreeBytePrefix -> Utf8ThreeByteLength
                leadingByte and Utf8FourBytePrefixMask == Utf8FourBytePrefix -> Utf8FourByteLength
                else -> Utf8SingleByteLength
            }
        if (continuationBytes + Utf8SingleByteLength < expectedLength) end = leadingIndex
    }
    return bytes.copyOfRange(0, end)
}

internal fun tailUtf8Bytes(
    value: String,
    maxBytes: Int,
): ByteArray {
    val bytes = value.toByteArray(Charsets.UTF_8)
    return when {
        maxBytes <= 0 -> {
            byteArrayOf()
        }

        bytes.size <= maxBytes -> {
            bytes
        }

        else -> {
            var start = bytes.size - maxBytes
            while (start < bytes.size && bytes[start].toInt() and Utf8ContinuationMask == Utf8ContinuationTag) {
                start += 1
            }
            bytes.copyOfRange(start, bytes.size)
        }
    }
}
