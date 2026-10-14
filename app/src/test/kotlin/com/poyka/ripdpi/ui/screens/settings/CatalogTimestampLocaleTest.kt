package com.poyka.ripdpi.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class CatalogTimestampLocaleTest {
    @Test
    fun `catalog dates follow the supplied app locale while system language stays English`() {
        val previousLocale = Locale.getDefault()
        val previousZone = TimeZone.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tbilisi"))
        try {
            val english = "Mar 12, 13:00"
            val hindi = "मार्च 12, 13:00"
            listOf(Locale.ENGLISH to english, Locale.forLanguageTag("hi") to hindi, Locale.ENGLISH to english)
                .forEach { (locale, expected) ->
                    assertEquals(expected, formatHostPackGeneratedAt("2026-03-12T09:00:00Z", locale))
                    assertEquals(expected, formatHostPackFetchedAt(1_773_306_000_000, locale))
                    assertEquals(expected, formatStrategyPackFetchedAt(1_773_306_000_000, locale))
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
            assertNull(formatHostPackGeneratedAt(timestamp, Locale.forLanguageTag("hi")))
        }
    }

    @Test
    fun `catalog dates retain system time zone and support the epoch boundary`() {
        val previousZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("Jan 1, 00:00", formatHostPackFetchedAt(0L, Locale.ENGLISH))
            assertEquals("Dec 31, 23:59", formatStrategyPackFetchedAt(-1L, Locale.ENGLISH))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tbilisi"))
            assertNotEquals("Jan 1, 00:00", formatHostPackFetchedAt(0L, Locale.ENGLISH))
            assertEquals("Jan 1, 04:00", formatHostPackGeneratedAt("1970-01-01T00:00:00Z", Locale.ENGLISH))
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }
}
