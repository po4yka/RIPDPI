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
    fun `filteredLogs keeps only selected types`() {
        val dnsEntry = sampleLogEntry(id = 1, type = LogType.DNS)
        val errorEntry = sampleLogEntry(id = 2, type = LogType.ERR)

        val uiState =
            LogsUiState(
                logs = listOf(dnsEntry, errorEntry),
                activeFilters = setOf(LogType.ERR),
            )

        assertEquals(listOf(errorEntry), uiState.filteredLogs)
    }

    @Test
    fun `latestLog returns newest entry in buffer`() {
        val firstEntry = sampleLogEntry(id = 1, type = LogType.CONN)
        val latestEntry = sampleLogEntry(id = 2, type = LogType.WARN)

        val uiState = LogsUiState(logs = listOf(firstEntry, latestEntry))

        assertEquals(latestEntry, uiState.latestLog)
    }
}

private fun sampleLogEntry(
    id: Long,
    type: LogType,
    message: String = "entry-$id",
): LogEntry =
    LogEntry(
        id = id,
        timestamp = "12:00:0$id",
        type = type,
        message = message,
    )
