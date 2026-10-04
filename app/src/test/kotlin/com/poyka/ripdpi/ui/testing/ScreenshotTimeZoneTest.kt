package com.poyka.ripdpi.ui.testing

import com.poyka.ripdpi.ui.screenshot.withUtcScreenshotTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ScreenshotTimeZoneTest {
    @Test
    fun `fixed fixture epochs format identically across host time zones`() {
        val original = TimeZone.getDefault()
        try {
            listOf("Asia/Tbilisi", "America/New_York").forEach { host ->
                TimeZone.setDefault(TimeZone.getTimeZone(host))
                val formatted =
                    withUtcScreenshotTime {
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(1_700_000_000_000L))
                    }
                assertEquals("2023-11-14 22:13:20", formatted)
                assertEquals(host, TimeZone.getDefault().id)
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `failed capture restores the previous time zone`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tbilisi"))
            assertThrows(IllegalStateException::class.java) {
                withUtcScreenshotTime<Unit> { throw IllegalStateException("capture failed") }
            }
            assertEquals("Asia/Tbilisi", TimeZone.getDefault().id)
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
