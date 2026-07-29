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
    }

    private val logDir = File(filesDir, LOG_DIR)
    private val logFile = File(logDir, LOG_FILE)
    private val prevLogFile = File(logDir, PREV_LOG_FILE)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    private var writeTruncated = false

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
                writeTruncated = writeTruncated || encodedLine.size > lineBytes.size
                rotateIfNeeded(lineBytes.size)
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
                            truncated = writeTruncated || totalBytes > maxFileSize,
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
            prevLogFile.delete()
            logFile.renameTo(prevLogFile)
        }
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
        while (start < bytes.size && bytes[start].toInt() and 0xC0 == 0x80) {
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
    if (maxBytes <= 0) return byteArrayOf()
    val bytes = value.toByteArray(Charsets.UTF_8)
    if (bytes.size.toLong() <= maxBytes) return bytes
    var end = maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    while (end > 0 && bytes[end].toInt() and 0xC0 == 0x80) {
        end -= 1
    }
    return bytes.copyOf(end)
}
