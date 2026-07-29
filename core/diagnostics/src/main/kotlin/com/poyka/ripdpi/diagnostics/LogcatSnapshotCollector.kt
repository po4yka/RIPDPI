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
                    val boundedBytes = tailUtf8Bytes(output, MAX_LOGCAT_BYTES)
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
                    readBounded(reader)
                }
            } finally {
                process.destroy()
                process.waitFor()
            }
        }

        private fun readBounded(reader: java.io.BufferedReader): String {
            val buffer = RollingByteTail(MAX_LOGCAT_BYTES + MAX_UTF8_BYTES_PER_CODE_POINT)
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

private const val MAX_UTF8_BYTES_PER_CODE_POINT = 4

private class RollingByteTail(
    private val capacity: Int,
) {
    private var bytes = ByteArray(0)

    fun append(chunk: ByteArray) {
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

    fun toByteArray(): ByteArray {
        var start = 0
        while (start < bytes.size && bytes[start].toInt() and 0xC0 == 0x80) {
            start += 1
        }
        return bytes.copyOfRange(start, bytes.size)
    }
}

internal fun tailUtf8Bytes(
    value: String,
    maxBytes: Int,
): ByteArray {
    if (maxBytes <= 0) return byteArrayOf()
    val bytes = value.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return bytes
    var start = bytes.size - maxBytes
    while (start < bytes.size && bytes[start].toInt() and 0xC0 == 0x80) {
        start += 1
    }
    return bytes.copyOfRange(start, bytes.size)
}
