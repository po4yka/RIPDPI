package com.poyka.ripdpi.diagnostics.crash

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Synchronous crash report writer installed as the default [Thread.UncaughtExceptionHandler].
 *
 * This class has no Hilt dependencies -- it is instantiated manually in
 * [com.poyka.ripdpi.RipDpiApp.onCreate] before DI is available.
 */
class CrashReportWriter private constructor(
    private val crashDir: File,
    private val appVersionName: String,
    private val appVersionCode: Long,
    private val previousHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    @Suppress("TooGenericExceptionCaught")
    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        try {
            val json = buildReportJson(thread, throwable)
            writeSync(json)
        } catch (_: Throwable) {
            // Swallow everything -- we must not prevent the system handler from running.
        } finally {
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun buildReportJson(
        thread: Thread,
        throwable: Throwable,
    ): String {
        val sb = StringBuilder(INITIAL_JSON_CAPACITY)
        sb.append('{')
        appendJsonString(sb, "timestamp", formatTimestamp())
        sb.append(',')
        appendJsonString(sb, "exceptionClass", throwable.javaClass.name)
        sb.append(',')
        appendJsonString(sb, "message", throwable.message ?: "")
        sb.append(',')
        appendJsonString(sb, "stacktrace", throwable.stackTraceToString())
        sb.append(',')
        appendJsonString(sb, "threadName", thread.name)
        sb.append(',')
        appendJsonString(sb, "deviceModel", Build.MODEL)
        sb.append(',')
        appendJsonString(sb, "deviceManufacturer", Build.MANUFACTURER)
        sb.append(',')
        appendJsonString(sb, "androidVersion", Build.VERSION.RELEASE)
        sb.append(',')
        appendJsonInt(sb, "sdkInt", Build.VERSION.SDK_INT)
        sb.append(',')
        appendJsonString(sb, "appVersionName", appVersionName)
        sb.append(',')
        appendJsonLong(sb, "appVersionCode", appVersionCode)
        sb.append('}')
        return sb.toString()
    }

    private fun writeSync(json: String) {
        crashDir.mkdirs()
        val file = File(crashDir, CRASH_FILE_NAME)
        FileOutputStream(file).use { fos ->
            fos.write(json.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
    }

    companion object {
        private const val INITIAL_JSON_CAPACITY = 4096
        internal const val CRASH_DIR_NAME = "crash-reports"
        internal const val CRASH_FILE_NAME = "crash_latest.json"

        fun install(
            context: Context,
            versionName: String,
            versionCode: Long,
        ) {
            val crashDir = File(context.filesDir, CRASH_DIR_NAME)
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            val writer = CrashReportWriter(crashDir, versionName, versionCode, previous)
            Thread.setDefaultUncaughtExceptionHandler(writer)
        }
    }
}

private fun formatTimestamp(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(Date())
}

private fun appendJsonString(
    sb: StringBuilder,
    key: String,
    value: String,
) {
    sb.append('"').append(key).append("\":\"")
    escapeJson(sb, value)
    sb.append('"')
}

private fun appendJsonInt(
    sb: StringBuilder,
    key: String,
    value: Int,
) {
    sb
        .append('"')
        .append(key)
        .append("\":")
        .append(value)
}

private fun appendJsonLong(
    sb: StringBuilder,
    key: String,
    value: Long,
) {
    sb
        .append('"')
        .append(key)
        .append("\":")
        .append(value)
}

private const val AsciiControlLimit = 0x20

private fun escapeJson(
    sb: StringBuilder,
    value: String,
) {
    for (ch in value) {
        when (ch) {
            '"' -> {
                sb.append("\\\"")
            }

            '\\' -> {
                sb.append("\\\\")
            }

            '\n' -> {
                sb.append("\\n")
            }

            '\r' -> {
                sb.append("\\r")
            }

            '\t' -> {
                sb.append("\\t")
            }

            else -> {
                if (ch.code < AsciiControlLimit) {
                    sb.append("\\u").append(String.format(Locale.US, "%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
    }
}
