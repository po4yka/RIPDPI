package com.poyka.ripdpi.activities

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsUiCoreSupportTest {
    @Test
    fun `format timestamp uses injected zone`() {
        val support =
            DiagnosticsUiCoreSupport(
                object : DiagnosticsUiFormatter() {
                    override val locale: Locale = Locale.US
                    override val zoneId: ZoneId = ZoneId.of("America/New_York")
                },
            )

        assertEquals("Dec 31, 19:00", support.formatTimestamp(0L))
    }

    @Test
    fun `formatters use injected locale`() {
        val support =
            DiagnosticsUiCoreSupport(
                object : DiagnosticsUiFormatter() {
                    override val locale: Locale = Locale.FRANCE
                    override val zoneId: ZoneId = ZoneId.of("UTC")
                },
            )

        assertEquals("1,5 KB", support.formatBytes(1_500L))
        assertEquals("1,5 Kbps", support.formatBps(1_500L))
        assertEquals("2h 05m", support.formatDurationMs(7_500_000L))
    }
}
