package com.poyka.ripdpi.diagnostics

import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.asLog
import logcat.logcat

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
            const val MAX_LOGCAT_BYTES = 512 * 1024
        }

        open suspend fun capture(): LogcatSnapshot? =
            withContext(Dispatchers.IO) {
                val output =
                    try {
                        readLogcatOutput()
                    } catch (error: Exception) {
                        logcat(LogPriority.ERROR) { "Failed to collect logs\n${error.asLog()}" }
                        null
                    }

                if (output.isNullOrBlank()) {
                    null
                } else {
                    LogcatSnapshot(
                        content = output,
                        captureScope = AppVisibleSnapshotScope,
                        byteCount = output.toByteArray(Charsets.UTF_8).size,
                    )
                }
            }

        // Filter to app's own PID to avoid capturing logs from other apps.
        protected open fun readLogcatOutput(): String {
            val process =
                Runtime.getRuntime().exec(arrayOf("logcat", "--pid=${android.os.Process.myPid()}", "-d"))
            try {
                process.errorStream.close()
                val output = process.inputStream.bufferedReader().use { reader ->
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
