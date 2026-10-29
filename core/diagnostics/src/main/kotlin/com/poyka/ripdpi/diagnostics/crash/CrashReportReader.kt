package com.poyka.ripdpi.diagnostics.crash

import co.touchlab.kermit.Logger
import com.poyka.ripdpi.diagnostics.export.redactDiagnosticsFreeText
import com.poyka.ripdpi.serialization.RipDpiJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CrashReportReader(
    private val filesDir: File,
) {
    private val json = RipDpiJson

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
                appendLine(redactCrashText(report.stacktrace))
            }
        return title to body
    }
}

// Exception messages can carry proxy links, tokens and addresses. Skip the logcat
// host-name rule here: it would also erase package names from the stack frames.
private val AnySchemeUriRegex = Regex("\\b[a-z][a-z0-9+.-]*://\\S+", RegexOption.IGNORE_CASE)

internal fun redactCrashText(value: String): String =
    redactDiagnosticsFreeText(value).replace(AnySchemeUriRegex, "<uri-redacted>")
