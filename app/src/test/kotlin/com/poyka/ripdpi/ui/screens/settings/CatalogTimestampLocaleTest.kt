package com.poyka.ripdpi.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogTimestampLocaleTest {
    @Test
    fun `catalog dates follow the supplied app locale while system language stays English`() {
        val previousLocale = Locale.getDefault()
        val previousZone = TimeZone.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tbilisi"))
        try {
            val english = "Mar 12, 13:00"
            val hindi = "12 मार्च, 13:00"
            listOf(Locale.ENGLISH to english, Locale.forLanguageTag("hi") to hindi, Locale.ENGLISH to english)
                .forEach { (locale, expected) ->
                    assertEquals(expected, formatHostPackGeneratedAt("2026-03-12T09:00:00Z", locale, is24Hour = true))
                    assertEquals(Locale.ENGLISH, Locale.getDefault())
                }
        } finally {
            Locale.setDefault(previousLocale)
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun `invalid generated dates keep the existing fallback`() {
        listOf("", "not a timestamp", "2026-03-12").forEach { timestamp ->
            assertNull(formatHostPackGeneratedAt(timestamp, Locale.forLanguageTag("hi"), is24Hour = true))
        }
    }

    @Test
    fun `catalog dates retain system time zone and the device clock`() {
        val previousZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("Jan 1, 00:00", formatHostPackGeneratedAt("1970-01-01T00:00:00Z", Locale.ENGLISH, true))
            assertEquals(
                "Jan 1, 12:00 AM",
                formatHostPackGeneratedAt("1970-01-01T00:00:00Z", Locale.ENGLISH, is24Hour = false),
            )
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tbilisi"))
            assertEquals("Jan 1, 04:00", formatHostPackGeneratedAt("1970-01-01T00:00:00Z", Locale.ENGLISH, true))
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }
}
