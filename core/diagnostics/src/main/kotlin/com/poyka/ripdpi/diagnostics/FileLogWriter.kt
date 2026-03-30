package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileLogWriter(
    private val filesDir: File,
    private val maxFileSize: Long = MAX_LOG_FILE_BYTES,
) : LogWriter() {
    companion object {
        const val MAX_LOG_FILE_BYTES = 512L * 1024L
        private const val LOG_DIR = "logs"
        private const val LOG_FILE = "app_log.txt"
        private const val PREV_LOG_FILE = "app_log.prev.txt"
    }

    private val logDir = File(filesDir, LOG_DIR)
    private val logFile = File(logDir, LOG_FILE)
    private val prevLogFile = File(logDir, PREV_LOG_FILE)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    override fun isLoggable(
        tag: String,
        severity: Severity,
    ): Boolean = severity >= Severity.Warn

    override fun log(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
    ) {
        synchronized(lock) {
            try {
                ensureLogDir()
                rotateIfNeeded()
                val timestamp = dateFormat.format(Date())
                val line =
                    buildString {
                        append('[').append(timestamp).append("] [")
                        append(severity.name.uppercase(Locale.US)).append("] [")
                        append(tag).append("] ")
                        append(message)
                        if (throwable != null) {
                            append('\n').append(throwable.stackTraceToString())
                        }
                        append('\n')
                    }
                FileOutputStream(logFile, true).use { fos ->
                    OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                        writer.write(line)
                    }
                }
            } catch (_: Exception) {
                // Logging must never crash the app
            }
        }
    }

    fun readLogContent(): String? =
        synchronized(lock) {
            try {
                buildString {
                    if (prevLogFile.exists()) {
                        append(prevLogFile.readText(Charsets.UTF_8))
                    }
                    if (logFile.exists()) {
                        append(logFile.readText(Charsets.UTF_8))
                    }
                }.takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
        }

    private fun ensureLogDir() {
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
    }

    private fun rotateIfNeeded() {
        if (logFile.exists() && logFile.length() >= maxFileSize) {
            prevLogFile.delete()
            logFile.renameTo(prevLogFile)
        }
    }
}
