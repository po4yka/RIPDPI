package com.poyka.ripdpi.activities

import org.junit.Assert.assertEquals
import org.junit.Test

class LogsViewModelTest {
    @Test
    fun `classifyLogType detects dns entries`() {
        assertEquals(LogType.DNS, classifyLogType("DNS resolver switched to 1.1.1.1"))
    }

    @Test
    fun `classifyLogType detects warning entries`() {
        assertEquals(LogType.WARN, classifyLogType("Warning: fallback resolver is active"))
    }

    @Test
    fun `classifyLogType detects error entries`() {
        assertEquals(LogType.ERR, classifyLogType("Proxy service failed to start"))
    }

    @Test
    fun `classifyLogType falls back to connection entries`() {
        assertEquals(LogType.CONN, classifyLogType("VPN service started"))
    }

    @Test
    fun `filteredLogs keeps only selected subsystem and severity`() {
        val proxyEntry = sampleLogEntry(id = "1", subsystem = LogSubsystem.Proxy, severity = LogSeverity.Info)
        val errorEntry = sampleLogEntry(id = "2", subsystem = LogSubsystem.Service, severity = LogSeverity.Error)

        val uiState =
            LogsUiState(
                logs = listOf(proxyEntry, errorEntry),
                activeSubsystems = setOf(LogSubsystem.Service),
                activeSeverities = setOf(LogSeverity.Error),
            )

        assertEquals(listOf(errorEntry), uiState.filteredLogs)
    }

    @Test
    fun `filteredLogs can restrict to active session only`() {
        val inactive = sampleLogEntry(id = "1", isActiveSession = false)
        val active = sampleLogEntry(id = "2", isActiveSession = true)

        val uiState =
            LogsUiState(
                logs = listOf(inactive, active),
                showActiveSessionOnly = true,
            )

        assertEquals(listOf(active), uiState.filteredLogs)
    }

    @Test
    fun `latestLog returns newest entry in buffer`() {
        val firstEntry = sampleLogEntry(id = "1", createdAtMs = 1)
        val latestEntry = sampleLogEntry(id = "2", createdAtMs = 2)

        val uiState = LogsUiState(logs = listOf(firstEntry, latestEntry))

        assertEquals(latestEntry, uiState.latestLog)
    }
}

private fun sampleLogEntry(
    id: String,
    createdAtMs: Long = id.toLong(),
    subsystem: LogSubsystem = LogSubsystem.Diagnostics,
    severity: LogSeverity = LogSeverity.Info,
    message: String = "entry-$id",
    isActiveSession: Boolean = true,
): LogEntry =
    LogEntry(
        id = id,
        createdAtMs = createdAtMs,
        timestamp = "12:00:${id.takeLast(2)}",
        subsystem = subsystem,
        severity = severity,
        message = message,
        source = "test",
        isActiveSession = isActiveSession,
    )
