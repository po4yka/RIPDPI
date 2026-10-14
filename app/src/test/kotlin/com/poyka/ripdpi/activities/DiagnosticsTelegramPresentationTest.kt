package com.poyka.ripdpi.activities

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
    fun `numbers follow the requested locale without changing transferred bytes`() {
        listOf(Locale.US, Locale.GERMAN, Locale.US).forEach { locale ->
            val separator = if (locale == Locale.GERMAN) ',' else '.'
            assertEquals("1${separator}5 Mbps", formatTelegramThroughput(187_500L, locale))
            assertEquals("1${separator}5 MB", formatTelegramTransferBytes(1_500_000L, locale))
        }
        assertEquals("999 B", formatTelegramTransferBytes(999L, Locale.GERMAN))
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
