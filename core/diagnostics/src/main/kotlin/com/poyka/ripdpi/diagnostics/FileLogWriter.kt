package com.poyka.ripdpi.diagnostics

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
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
        private const val TRUNCATED_MARKER_FILE = "app_log.truncated"
        private const val PREV_TRUNCATED_MARKER_FILE = "app_log.prev.truncated"
    }

    private val logDir = File(filesDir, LOG_DIR)
    private val logFile = File(logDir, LOG_FILE)
    private val prevLogFile = File(logDir, PREV_LOG_FILE)
    private val truncatedMarkerFile = File(logDir, TRUNCATED_MARKER_FILE)
    private val prevTruncatedMarkerFile = File(logDir, PREV_TRUNCATED_MARKER_FILE)
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
                val encodedLine = line.toByteArray(Charsets.UTF_8)
                val lineBytes = truncateUtf8Bytes(line, maxFileSize)
                rotateIfNeeded(lineBytes.size)
                if (encodedLine.size > lineBytes.size) {
                    check(truncatedMarkerFile.exists() || truncatedMarkerFile.createNewFile()) {
                        "Unable to persist app log truncation marker"
                    }
                }
                FileOutputStream(logFile, true).use { fos ->
                    fos.write(lineBytes)
                }
            } catch (_: Exception) {
                // Logging must never crash the app
            }
        }
    }

    fun readLogSnapshot(): FileLogSnapshot? =
        synchronized(lock) {
            try {
                val totalBytes = listOf(prevLogFile, logFile).filter(File::exists).sumOf(File::length)
                var remaining = maxFileSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val currentBytes = readUtf8Tail(logFile, remaining)
                remaining -= currentBytes.size
                val previousBytes = readUtf8Tail(prevLogFile, remaining)
                val bytes = previousBytes + currentBytes
                bytes
                    .takeIf(ByteArray::isNotEmpty)
                    ?.let {
                        FileLogSnapshot(
                            content = it.toString(Charsets.UTF_8),
                            byteCount = it.size,
                            truncated =
                                truncatedMarkerFile.exists() ||
                                    prevTruncatedMarkerFile.exists() ||
                                    totalBytes > maxFileSize,
                        )
                    }
            } catch (_: Exception) {
                null
            }
        }

    fun readLogContent(): String? = readLogSnapshot()?.content

    private fun ensureLogDir() {
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
    }

    private fun rotateIfNeeded(pendingBytes: Int) {
        if (logFile.exists() && logFile.length() + pendingBytes > maxFileSize) {
            deleteIfPresent(prevLogFile)
            deleteIfPresent(prevTruncatedMarkerFile)
            check(logFile.renameTo(prevLogFile)) { "Unable to rotate app log" }
            if (truncatedMarkerFile.exists()) {
                check(truncatedMarkerFile.renameTo(prevTruncatedMarkerFile)) {
                    "Unable to rotate app log truncation marker"
                }
            }
        }
    }

    private fun deleteIfPresent(file: File) {
        check(!file.exists() || file.delete()) { "Unable to delete rotated app log artifact" }
    }

    private fun readUtf8Tail(
        file: File,
        maxBytes: Int,
    ): ByteArray {
        if (!file.exists() || maxBytes <= 0) return byteArrayOf()
        val byteCount = minOf(file.length(), maxBytes.toLong()).toInt()
        val bytes = ByteArray(byteCount)
        RandomAccessFile(file, "r").use { input ->
            input.seek(file.length() - byteCount)
            input.readFully(bytes)
        }
        var start = 0
        while (start < bytes.size && bytes[start].toInt() and Utf8ContinuationMask == Utf8ContinuationTag) {
            start += 1
        }
        return bytes.copyOfRange(start, bytes.size)
    }
}

data class FileLogSnapshot(
    val content: String,
    val byteCount: Int,
    val truncated: Boolean,
)

internal fun truncateUtf8Bytes(
    value: String,
    maxBytes: Long,
): ByteArray {
    val bytes = value.toByteArray(Charsets.UTF_8)
    return when {
        maxBytes <= 0 -> {
            byteArrayOf()
        }

        bytes.size.toLong() <= maxBytes -> {
            bytes
        }

        else -> {
            var end = maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            while (end > 0 && bytes[end].toInt() and Utf8ContinuationMask == Utf8ContinuationTag) {
                end -= 1
            }
            bytes.copyOf(end)
        }
    }
}
