package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class LogcatSnapshot(
    val content: String,
    val captureScope: String,
    val byteCount: Int,
)

open class LogcatSnapshotCollector
    @Inject
    constructor() {
        companion object {
            const val AppVisibleSnapshotScope = "app_visible_snapshot"
            const val TimeBoundSnapshotScope = "time_bound_snapshot"
            const val MAX_LOGCAT_BYTES = 512 * 1024
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
                    } catch (error: Exception) {
                        Logger.e(error) { "Failed to collect logs" }
                        null
                    }

                if (output.isNullOrBlank()) {
                    null
                } else {
                    val scope =
                        if (sinceTimestampMs != null) TimeBoundSnapshotScope else AppVisibleSnapshotScope
                    LogcatSnapshot(
                        content = output,
                        captureScope = scope,
                        byteCount = output.toByteArray(Charsets.UTF_8).size,
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
                }
            val process = Runtime.getRuntime().exec(command.toTypedArray())
            try {
                process.errorStream.close()
                val output =
                    process.inputStream.bufferedReader().use { reader ->
                        val buffer = StringBuilder()
                        val charBuf = CharArray(8192)
                        var totalBytes = 0
                        while (true) {
                            val charsRead = reader.read(charBuf)
                            if (charsRead == -1) break
                            totalBytes += charsRead * 2 // conservative UTF-16 estimate
                            if (totalBytes > MAX_LOGCAT_BYTES) {
                                buffer.append(charBuf, 0, charsRead)
                                break
                            }
                            buffer.append(charBuf, 0, charsRead)
                        }
                        buffer.toString()
                    }
                return output
            } finally {
                process.destroy()
                process.waitFor()
            }
        }
    }
