package com.poyka.ripdpi.diagnostics.export

import com.poyka.ripdpi.diagnostics.FileLogWriter
import kotlinx.serialization.Serializable

private const val DefaultStartupJournalBytes = 32 * 1024

@Serializable
internal data class DiagnosticsArchiveAppliedLimits(
    val telemetrySamples: Int,
    val nativeEvents: Int,
    val snapshots: Int,
    val logcatBytes: Int,
    val appLogBytes: Long = FileLogWriter.MAX_LOG_FILE_BYTES,
    val startupJournalBytes: Int = DefaultStartupJournalBytes,
)

@Serializable
internal data class DiagnosticsArchiveTruncation(
    val telemetrySamples: Boolean = false,
    val nativeEvents: Boolean = false,
    val snapshots: Boolean = false,
    val contexts: Boolean = false,
    val logcat: Boolean = false,
    val appLog: Boolean = false,
    val startupJournal: Boolean = false,
)
