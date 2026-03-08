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
}
