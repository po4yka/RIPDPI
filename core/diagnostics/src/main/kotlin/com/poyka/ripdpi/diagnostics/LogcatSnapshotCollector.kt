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

                if (output.isNullOrBlank()) {
                    null
                } else {
                    val outputBytes = output.toByteArray(Charsets.UTF_8)
                    val boundedBytes =
                        if (sinceTimestampMs != null) {
                            headAndTailUtf8Bytes(output, MAX_LOGCAT_BYTES)
                        } else {
                            tailUtf8Bytes(output, MAX_LOGCAT_BYTES)
                        }
                    val scope =
                        if (sinceTimestampMs != null) TimeBoundSnapshotScope else AppVisibleSnapshotScope
                    LogcatSnapshot(
                        content = boundedBytes.toString(Charsets.UTF_8),
                        captureScope = scope,
                        byteCount = boundedBytes.size,
                        truncated = outputBytes.size > boundedBytes.size,
                    )
                }
            }

        /**
         * Filter to app's own PID to avoid capturing logs from other apps.
         * When [sinceTimestampMs] is provided, uses `-T` to capture logs from that
         * point forward instead of relying on the current circular buffer contents.
         */
        protected open fun readLogcatOutput(sinceTimestampMs: Long? = null): String {
            val command =
                buildList {
                    add("logcat")
                    add("--pid=${android.os.Process.myPid()}")
                    add("-d")
                    if (sinceTimestampMs != null) {
                        val formatter = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                        val timestamp = formatter.format(Date(sinceTimestampMs))
                        add("-T")
                        add(timestamp)
                        add("-v")
                        add("epoch")
                    }
                    // Filter out noisy framework tags (View, Choreographer) that
                    // drown out diagnostic-relevant log lines.
                    add("-s")
                    add("ripdpi-native:V")
                    add("ripdpi:V")
                    add("AndroidRuntime:E")
                    add("*:W")
                }
            val process = Runtime.getRuntime().exec(command.toTypedArray())
            try {
                process.errorStream.close()
                return process.inputStream.bufferedReader().use { reader ->
                    readBounded(reader, preserveStart = sinceTimestampMs != null)
                }
            } finally {
                process.destroy()
                process.waitFor()
            }
        }

        private fun readBounded(
            reader: java.io.BufferedReader,
            preserveStart: Boolean,
        ): String {
            val capacity = MAX_LOGCAT_BYTES + MaxUtf8BytesPerCodePoint
            val buffer: RollingByteBuffer =
                if (preserveStart) RollingByteHeadAndTail(capacity) else RollingByteTail(capacity)
            val charBuf = CharArray(READ_BUFFER_CHARS)
            var charsRead = reader.read(charBuf)
            while (charsRead != -1) {
                val chunk = String(charBuf, 0, charsRead)
                buffer.append(chunk.toByteArray(Charsets.UTF_8))
                charsRead = reader.read(charBuf)
            }
            return buffer.toByteArray().toString(Charsets.UTF_8)
        }
    }

internal fun LogcatSnapshot.historyCoverageWarnings(requestedSinceTimestampMs: Long?): List<String> =
    if (requestedSinceTimestampMs == null || captureScope != LogcatSnapshotCollector.TimeBoundSnapshotScope) {
        emptyList()
    } else {
        val oldestRetainedTimestampMs =
            content
                .lineSequence()
                .mapNotNull(::parseEpochLogcatTimestampMs)
                .firstOrNull()
        when {
            oldestRetainedTimestampMs == null -> {
                listOf("logcat_history_coverage_unverified")
            }

            oldestRetainedTimestampMs - requestedSinceTimestampMs > LogcatHistoryStartToleranceMs -> {
                val missingPrefixMs = oldestRetainedTimestampMs - requestedSinceTimestampMs
                listOf("logcat_history_incomplete:missing_prefix_ms=$missingPrefixMs")
            }

            else -> {
                emptyList()
            }
        }
    }

private fun parseEpochLogcatTimestampMs(line: String): Long? =
    EpochLogcatTimestampRegex
        .find(line)
        ?.groupValues
        ?.get(1)
        ?.let { token ->
            token.substringBefore('.').toLongOrNull()?.let { seconds ->
                val milliseconds =
                    token
                        .substringAfter('.', missingDelimiterValue = "")
                        .padEnd(EpochMillisecondsDigits, '0')
                        .take(EpochMillisecondsDigits)
                        .toLongOrNull()
                        ?: 0L
                seconds * MillisecondsPerSecond + milliseconds
            }
        }

internal const val Utf8ContinuationMask = 0xC0
internal const val Utf8ContinuationTag = 0x80
private const val MaxUtf8BytesPerCodePoint = 4
private const val MillisecondsPerSecond = 1_000L
private const val LogcatHistoryStartToleranceMs = 5_000L
private const val EpochMillisecondsDigits = 3
private val EpochLogcatTimestampRegex = Regex("^\\s*(\\d{10,}(?:\\.\\d+)?)\\s")

private interface RollingByteBuffer {
    fun append(chunk: ByteArray)

    fun toByteArray(): ByteArray
}

private class RollingByteTail(
    private val capacity: Int,
) : RollingByteBuffer {
    private var bytes = ByteArray(0)

    override fun append(chunk: ByteArray) {
        bytes =
            when {
                chunk.size >= capacity -> {
                    chunk.copyOfRange(chunk.size - capacity, chunk.size)
                }

                bytes.size + chunk.size <= capacity -> {
                    bytes + chunk
                }

                else -> {
                    val keep = capacity - chunk.size
                    bytes.copyOfRange(bytes.size - keep, bytes.size) + chunk
                }
            }
    }

    override fun toByteArray(): ByteArray {
        var start = 0
        while (start < bytes.size && bytes[start].toInt() and Utf8ContinuationMask == Utf8ContinuationTag) {
            start += 1
        }
        return bytes.copyOfRange(start, bytes.size)
    }
}

private class RollingByteHeadAndTail(
    private val capacity: Int,
) : RollingByteBuffer {
    private val headCapacity = capacity
    private val tail = RollingByteTail(capacity / 2 + MaxUtf8BytesPerCodePoint)
    private var head = ByteArray(0)
    private var totalByteCount = 0L

    override fun append(chunk: ByteArray) {
        totalByteCount += chunk.size
        if (head.size < headCapacity) {
            head += chunk.copyOfRange(0, minOf(chunk.size, headCapacity - head.size))
        }
        tail.append(chunk)
    }

    override fun toByteArray(): ByteArray =
        if (totalByteCount <= capacity) {
            head
        } else {
            headAndTailUtf8Bytes(
                head = head,
                tail = tail.toByteArray(),
                maxBytes = capacity,
            )
        }
}

internal fun headAndTailUtf8Bytes(
    value: String,
    maxBytes: Int,
): ByteArray {
    val bytes = value.toByteArray(Charsets.UTF_8)
    return headAndTailUtf8Bytes(bytes, bytes, maxBytes)
}

private fun headAndTailUtf8Bytes(
    head: ByteArray,
    tail: ByteArray,
    maxBytes: Int,
): ByteArray =
    when {
        maxBytes <= 0 -> {
            byteArrayOf()
        }

        head === tail && head.size <= maxBytes -> {
            head
        }

        maxBytes == 1 -> {
            utf8PrefixBytes(head, maxBytes)
        }

        else -> {
            val contentBudget = maxBytes - 1
            val headBudget = contentBudget / 2
            val tailBudget = contentBudget - headBudget
            val prefix = utf8PrefixBytes(head, headBudget)
            val suffix = tailUtf8Bytes(tail.toString(Charsets.UTF_8), tailBudget)
            prefix + byteArrayOf('\n'.code.toByte()) + suffix
        }
    }

private fun utf8PrefixBytes(
    bytes: ByteArray,
    maxBytes: Int,
): ByteArray {
    if (bytes.size <= maxBytes) return bytes
    var end = maxBytes
    while (end > 0 && bytes[end].toInt() and Utf8ContinuationMask == Utf8ContinuationTag) {
        end -= 1
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
