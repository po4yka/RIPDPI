package com.poyka.ripdpi.diagnostics.crash

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e) { "Corrupt crash report, deleting" }
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
