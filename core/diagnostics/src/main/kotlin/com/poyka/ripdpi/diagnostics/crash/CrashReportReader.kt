package com.poyka.ripdpi.diagnostics.crash

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import java.io.File

class CrashReportReader(
    private val filesDir: File,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val crashFile: File
        get() = File(filesDir, "${CrashReportWriter.CRASH_DIR_NAME}/${CrashReportWriter.CRASH_FILE_NAME}")

    @Suppress("TooGenericExceptionCaught")
    suspend fun read(): CrashReport? =
        withContext(Dispatchers.IO) {
            val file = crashFile
            if (!file.exists()) return@withContext null
            try {
                json.decodeFromString<CrashReport>(file.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Corrupt crash report, deleting\n${e.asLog()}" }
                file.delete()
                null
            }
        }

    fun delete() {
        crashFile.delete()
    }

    fun buildShareText(report: CrashReport): Pair<String, String> {
        val title = "RIPDPI Crash Report"
        val body =
            buildString {
                appendLine("RIPDPI Crash Report")
                appendLine("====================")
                appendLine("Time: ${report.timestamp}")
                appendLine("Version: ${report.appVersionName} (${report.appVersionCode})")
                append("Device: ${report.deviceManufacturer} ${report.deviceModel}")
                appendLine(" (Android ${report.androidVersion}, SDK ${report.sdkInt})")
                appendLine("Thread: ${report.threadName}")
                appendLine()
                appendLine(report.stacktrace)
            }
        return title to body
    }
}
