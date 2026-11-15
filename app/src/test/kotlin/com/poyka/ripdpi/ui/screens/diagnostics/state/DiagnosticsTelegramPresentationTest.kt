package com.poyka.ripdpi.ui.screens.diagnostics.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class DiagnosticsTelegramPresentationTest {
    @Test
    fun `native byte rates become bits at all unit boundaries`() {
        val expected =
            mapOf(
                0L to "0 bps",
                1L to "8 bps",
                124L to "992 bps",
                125L to "1.0 Kbps",
                124_999L to "1000.0 Kbps",
                125_000L to "1.0 Mbps",
                1_000_000L to "8.0 Mbps",
            )
        expected.forEach { (rate, label) -> assertEquals(label, formatTelegramThroughput(rate, Locale.US)) }
    }

    @Test
    fun `maximum native byte rate does not overflow signed Long`() {
        assertEquals("73786976294838.2 Mbps", formatTelegramThroughput(Long.MAX_VALUE, Locale.US))
        assertFalse(formatTelegramThroughput(Long.MAX_VALUE, Locale.GERMAN).startsWith("-"))
    }

    @Test
    fun `numbers follow the requested locale`() {
        listOf(Locale.US, Locale.GERMAN, Locale.US).forEach { locale ->
            val separator = if (locale == Locale.GERMAN) ',' else '.'
            assertEquals("1${separator}5 Mbps", formatTelegramThroughput(187_500L, locale))
        }
    }

    @Test
    fun `DC details represent each port and historical reachability without truncating IPv6`() {
        assertEquals(
            TelegramDcPresentation("DC1", "ok", 42, "[2001:db8::1]:443"),
            parseTelegramDcResult("DC1:ok:42ms:address=[2001:db8::1]:443;family=ipv6;path=direct"),
        )
        assertEquals(
            TelegramDcPresentation("DC1", "fail", null, "[2001:db8::1]:80"),
            parseTelegramDcResult("DC1:fail::address=[2001:db8::1]:80;family=ipv6;path=direct;stage=tcp_connect"),
        )
        assertEquals(
            TelegramDcPresentation("dc1", "ok", 45, "443:fail,80:ok:45ms"),
            parseTelegramDcResult("dc1:reachable=true:medianRttMs=45:ports=443:fail,80:ok:45ms"),
        )
        assertEquals(TelegramDcPresentation("DC1", "ok", 42, null), parseTelegramDcResult("DC1:ok:42ms"))
        assertNull(parseTelegramDcResult("dc_probe_aborted:cancelled"))
    }

    @Test
    fun `all native status and verdict codes are localized and unknown evidence has no mapping`() {
        val statuses =
            listOf(
                "ok",
                "slow",
                "stalled",
                "blocked",
                "partial",
                "error",
                "cancelled",
                "deadline_exceeded",
                "unknown",
                "not_run",
                "unreachable",
            )
        assertEquals(
            statuses.size,
            statuses
                .map(::telegramStatusLabelRes)
                .filterNotNull()
                .distinct()
                .size,
        )
        assertNull(telegramStatusLabelRes("Provider_Custom_Status"))
        assertNull(telegramStatusLabelRes("Slow"))
    }
}
