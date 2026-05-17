package com.poyka.ripdpi.activities

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import java.util.Locale

enum class LogType {
    DNS,
    CONN,
    ERR,
    WARN,
}

enum class LogSubsystem {
    Service,
    Proxy,
    Tunnel,
    Diagnostics,
}

enum class LogSeverity {
    Debug,
    Info,
    Warn,
    Error,
}

data class LogEntry(
    val id: String,
    val createdAtMs: Long,
    val timestamp: String,
    val subsystem: LogSubsystem,
    val severity: LogSeverity,
    val message: String,
    val source: String,
    val runtimeId: String? = null,
    val diagnosticsSessionId: String? = null,
    val isActiveSession: Boolean = false,
) {
    val dedupeKey: String =
        listOf(
            subsystem.name,
            severity.name,
            source,
            runtimeId.orEmpty(),
            diagnosticsSessionId.orEmpty(),
            createdAtMs.toString(),
            message,
        ).joinToString("|")
}

data class LogsUiState(
    val logs: ImmutableList<LogEntry> = persistentListOf(),
    val activeSubsystems: ImmutableSet<LogSubsystem> = LogSubsystem.entries.toImmutableSet(),
    val activeSeverities: ImmutableSet<LogSeverity> = LogSeverity.entries.toImmutableSet(),
    val showActiveSessionOnly: Boolean = false,
    val isAutoScroll: Boolean = true,
    val bufferCapacity: Int = MaxLogEntries,
    val isRefreshing: Boolean = false,
    val filteredLogs: ImmutableList<LogEntry> =
        filterLogs(
            logs = logs,
            subsystems = activeSubsystems,
            severities = activeSeverities,
            activeSessionOnly = showActiveSessionOnly,
        ).toImmutableList(),
) {
    val latestLog: LogEntry?
        get() = logs.lastOrNull()
}

internal fun filterLogs(
    logs: List<LogEntry>,
    subsystems: Set<LogSubsystem>,
    severities: Set<LogSeverity>,
    activeSessionOnly: Boolean,
): List<LogEntry> =
    logs
        .asSequence()
        .filter { entry ->
            entry.subsystem in subsystems &&
                entry.severity in severities &&
                (!activeSessionOnly || entry.isActiveSession)
        }.toList()

internal fun classifyLogType(message: String): LogType {
    val normalized = message.lowercase(Locale.ROOT)
    return when {
        "dns" in normalized -> LogType.DNS
        "warn" in normalized || "warning" in normalized -> LogType.WARN
        "error" in normalized || "fail" in normalized -> LogType.ERR
        else -> LogType.CONN
    }
}
